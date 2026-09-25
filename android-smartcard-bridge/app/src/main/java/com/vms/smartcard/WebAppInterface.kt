package com.vms.smartcard

import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * JavaScript Interface สำหรับให้หน้าเว็บ Google Apps Script สื่อสารกับ Native Android
 */
class WebAppInterface(private val activity: MainActivity) {

    /**
     * สั่งอ่านบัตรประชาชนจากฝั่ง JavaScript
     */
    @JavascriptInterface
    fun readCard() {
        activity.readCardAsync()
    }

    /**
     * ตรวจสอบว่ามีเครื่องอ่านบัตร Type-C เสียบอยู่หรือไม่
     */
    @JavascriptInterface
    fun isReaderConnected(): Boolean {
        return activity.isReaderConnected()
    }

    /**
     * แสดงข้อความ Toast แบบ Native บน Android
     */
    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * โหลดหน้าเว็บใหม่
     */
    @JavascriptInterface
    fun reload() {
        activity.runOnUiThread {
            activity.reloadPage()
        }
    }

    /**
     * ตรวจสอบเวอร์ชันของแอป
     */
    @JavascriptInterface
    fun getVersion(): String {
        return "1.0.0"
    }
}
