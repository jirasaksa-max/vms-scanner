package com.vms.smartcard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var usbManager: UsbManager

    private var activeDevice: UsbDevice? = null
    private var ccidReader: CcidCardReader? = null
    private var isReading = false
    private var lastReadCid = ""
    private var autoDetectJob: Job? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val TAG = "VmsMainActivity"
        private const val ACTION_USB_PERMISSION = "com.vms.smartcard.USB_PERMISSION"
        
        // Google Apps Script Web App URL ของระบบ VMS
        const val WEB_APP_URL = "https://script.google.com/macros/s/AKfycbyAyyWSW7XS58H9I8RgauHPRLWB1u0u4VzKuvMSy_MwLq9rz19eqMvqhUQwbusEhFHK/exec"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                Log.d(TAG, "USB Permission granted for: ${it.deviceName}")
                                onUsbDeviceReady(it)
                            }
                        } else {
                            Log.w(TAG, "USB Permission denied for device: ${device?.deviceName}")
                            notifyWebStatus("error", "ไม่ได้รับอนุญาตให้เข้าถึง USB")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB Device Attached")
                    checkConnectedUsbDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "USB Device Detached")
                    onUsbDeviceDetached()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        setupWebView()
        setupBackNavigation()
        registerUsbReceiver()

        // ตรวจสอบเครื่องอ่านบัตรที่เสียบค้างไว้ตั้งแต่ก่อนเปิดแอป
        checkConnectedUsbDevices()

        // โหลดหน้าเว็บ VMS
        webView.loadUrl(WEB_APP_URL)
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // เชื่อมต่อ JavaScript Interface ในชื่อ "AndroidSmartCard"
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidSmartCard")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE

                // แจ้งสถานะ Smart Card ให้หน้าเว็บทราบทันทีที่โหลดเสร็จ
                if (isReaderConnected()) {
                    notifyWebStatus("connected", "เชื่อมต่อเครื่องอ่าน Type-C แล้ว")
                } else {
                    notifyWebStatus("waiting", "กรุณาเสียบเครื่องอ่าน Type-C")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            // รองรับการกดเลือกไฟล์/เปิดกล้องบนหน้าเว็บ
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, 1001)
                } catch (e: Exception) {
                    fileUploadCallback = null
                    return false
                }
                return true
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            val result = if (resultCode == RESULT_OK) {
                data?.data?.let { arrayOf(it) }
            } else null
            fileUploadCallback?.onReceiveValue(result)
            fileUploadCallback = null
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    /**
     * ค้นหาเครื่องอ่านบัตร USB ที่เสียบอยู่
     */
    private fun checkConnectedUsbDevices() {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (isSmartCardReader(device)) {
                activeDevice = device
                requestUsbPermission(device)
                return
            }
        }
        activeDevice = null
        ccidReader?.disconnect()
        ccidReader = null
        notifyWebStatus("waiting", "กรุณาเสียบเครื่องอ่าน Type-C")
    }

    private fun isSmartCardReader(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            // Class 11 (0x0B) คือ USB CCID Smart Card Reader
            if (intf.interfaceClass == CcidCardReader.USB_CLASS_CCID) {
                return true
            }
        }
        return true
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            onUsbDeviceReady(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun onUsbDeviceReady(device: UsbDevice) {
        activeDevice = device
        ccidReader?.disconnect()
        ccidReader = CcidCardReader(usbManager, device)

        if (ccidReader?.connect() == true) {
            Log.d(TAG, "Smart Card Reader Connected Successfully!")
            notifyWebStatus("connected", "พร้อมอ่านบัตร (เสียบบัตรได้เลย)")
            startAutoCardDetection()
        } else {
            Log.e(TAG, "Failed to connect to Smart Card Reader")
            notifyWebStatus("error", "เชื่อมต่อเครื่องอ่านไม่สำเร็จ")
        }
    }

    private fun onUsbDeviceDetached() {
        stopAutoCardDetection()
        ccidReader?.disconnect()
        ccidReader = null
        activeDevice = null
        lastReadCid = ""
        notifyWebStatus("waiting", "เครื่องอ่านบัตรถูกถอดออก")
    }

    /**
     * ระบบวนตรวจสอบการเสียบบัตรประชาชนอัตโนมัติ (เสียบปุ๊บอ่านปั๊บทันที)
     */
    private fun startAutoCardDetection() {
        stopAutoCardDetection()
        autoDetectJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(1200)
                if (isReading || ccidReader == null) continue

                try {
                    val reader = ccidReader ?: continue
                    val atr = reader.powerOn()
                    if (atr != null && atr.isNotEmpty()) {
                        // พบการ์ดในช่องอ่าน!
                        readCardInternal(reader)
                    } else {
                        // ไม่มีการ์ดในช่องอ่าน -> ล้างสถานะบัตรล่าสุดเพื่อให้อ่านบัตรเดิมซ้ำได้เมื่อเสียบใหม่
                        lastReadCid = ""
                    }
                } catch (e: Exception) {
                    // Ignored in loop
                }
            }
        }
    }

    private fun stopAutoCardDetection() {
        autoDetectJob?.cancel()
        autoDetectJob = null
    }

    /**
     * สั่งอ่านบัตรจากภายนอก (ผ่านปุ่มกดบนหน้าเว็บ)
     */
    fun readCardAsync() {
        if (isReading) return
        val reader = ccidReader
        if (reader == null) {
            runOnUiThread {
                Toast.makeText(this, "กรุณาเสียบเครื่องอ่านบัตร Type-C ก่อนครับ", Toast.LENGTH_SHORT).show()
                notifyWebStatus("waiting", "ไม่พบเครื่องอ่านบัตร")
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            readCardInternal(reader, force = true)
        }
    }

    private suspend fun readCardInternal(reader: CcidCardReader, force: Boolean = false) {
        if (isReading) return
        isReading = true

        withContext(Dispatchers.Main) {
            notifyWebStatus("reading", "กำลังอ่านข้อมูลจากชิปการ์ด...")
        }

        try {
            val parser = ThaiIdCardParser(reader)
            val jsonResult = parser.readFullCard(includePhoto = true)

            withContext(Dispatchers.Main) {
                // ส่งผลลัพธ์ JSON เข้า JavaScript ในหน้าเว็บ
                val safeJsJson = jsonResult.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")
                webView.evaluateJavascript("if (typeof window.onSmartCardRead === 'function') { window.onSmartCardRead('$safeJsJson'); }", null)
                notifyWebStatus("connected", "อ่านข้อมูลบัตรเรียบร้อย")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Read card error", e)
            withContext(Dispatchers.Main) {
                notifyWebStatus("error", "อ่านบัตรผิดพลาด: ${e.message}")
            }
        } finally {
            delay(1000)
            isReading = false
        }
    }

    private fun notifyWebStatus(status: String, message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if (typeof window.onSmartCardStatus === 'function') { window.onSmartCardStatus('$status', '$message'); }",
                null
            )
        }
    }

    fun isReaderConnected(): Boolean {
        return ccidReader != null
    }

    fun reloadPage() {
        webView.reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoCardDetection()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        ccidReader?.disconnect()
    }
}
