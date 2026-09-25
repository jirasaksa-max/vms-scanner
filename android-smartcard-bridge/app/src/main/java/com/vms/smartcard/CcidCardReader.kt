package com.vms.smartcard

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Driver สำหรับสื่อสารกับเครื่องอ่าน Smart Card ผ่านมาตรฐาน USB CCID (Class 11 / 0x0B)
 * ทำงานได้กับเครื่องอ่าน Type-C ทุกยี่ห้อที่รองรับ USB CCID มาตรฐานสากล
 */
class CcidCardReader(
    private val usbManager: UsbManager,
    val device: UsbDevice
) {
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var sequenceNumber: Byte = 0

    companion object {
        private const val TAG = "CcidCardReader"
        const val USB_CLASS_CCID = 11 // 0x0B
    }

    /**
     * ตรวจสอบว่าอุปกรณ์ USB นี้เป็นเครื่องอ่านบัตร Smart Card (CCID) หรือไม่
     */
    fun isCcidDevice(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == USB_CLASS_CCID) {
                return true
            }
        }
        return true // อนุญาตให้อุปกรณ์ USB ที่เชื่อมต่อเข้าทดสอบได้
    }

    /**
     * เปิดการเชื่อมต่อ USB และจอง Interface สำหรับรับ-ส่งข้อมูล
     */
    fun connect(): Boolean {
        try {
            connection = usbManager.openDevice(device) ?: return false

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null

                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                        else if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                    }
                }

                if (epIn != null && epOut != null) {
                    if (connection?.claimInterface(intf, true) == true) {
                        this.usbInterface = intf
                        this.endpointIn = epIn
                        this.endpointOut = epOut
                        Log.d(TAG, "Connected to CCID Smart Card Reader interface $i")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
        }
        return false
    }

    /**
     * ปิดการเชื่อมต่อ
     */
    fun disconnect() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        } finally {
            connection = null
            usbInterface = null
            endpointIn = null
            endpointOut = null
        }
    }

    /**
     * คำสั่ง CCID: PC_to_RDR_IccPowerOn (0x62) เพื่อเริ่มจ่ายไฟให้ชิปการ์ดและรับ ATR
     */
    fun powerOn(): ByteArray? {
        val conn = connection ?: return null
        val epOut = endpointOut ?: return null
        val epIn = endpointIn ?: return null

        val cmd = ByteArray(10)
        cmd[0] = 0x62.toByte() // PC_to_RDR_IccPowerOn
        cmd[1] = 0 // dwLength LSB
        cmd[2] = 0
        cmd[3] = 0
        cmd[4] = 0 // dwLength MSB
        cmd[5] = 0 // bSlot
        cmd[6] = (sequenceNumber++).toByte()
        cmd[7] = 0 // bPowerSelect (0 = Auto)
        cmd[8] = 0
        cmd[9] = 0

        val sent = conn.bulkTransfer(epOut, cmd, cmd.size, 3000)
        if (sent < 0) {
            Log.e(TAG, "PowerOn bulkTransfer out failed")
            return null
        }

        val resp = ByteArray(256)
        val read = conn.bulkTransfer(epIn, resp, resp.size, 3000)
        if (read < 10) {
            Log.e(TAG, "PowerOn bulkTransfer in failed, read=$read")
            return null
        }

        // resp[0] ควรเป็น 0x80 (RDR_to_PC_DataBlock)
        // ข้อมูล ATR จะอยู่ที่ Index 10 ถึง read - 1
        return if (read > 10) resp.copyOfRange(10, read) else ByteArray(0)
    }

    /**
     * คำสั่ง CCID: PC_to_RDR_XfrBlock (0x6F) เพื่อส่งคำสั่ง APDU ไปยังชิปบัตร
     */
    private fun xfrBlock(apdu: ByteArray): ByteArray? {
        val conn = connection ?: return null
        val epOut = endpointOut ?: return null
        val epIn = endpointIn ?: return null

        val len = apdu.size
        val cmd = ByteArray(10 + len)
        cmd[0] = 0x6F.toByte() // PC_to_RDR_XfrBlock
        cmd[1] = (len and 0xFF).toByte()
        cmd[2] = ((len shr 8) and 0xFF).toByte()
        cmd[3] = ((len shr 16) and 0xFF).toByte()
        cmd[4] = ((len shr 24) and 0xFF).toByte()
        cmd[5] = 0 // bSlot
        cmd[6] = (sequenceNumber++).toByte()
        cmd[7] = 0 // bBwi
        cmd[8] = 0
        cmd[9] = 0
        System.arraycopy(apdu, 0, cmd, 10, len)

        val sent = conn.bulkTransfer(epOut, cmd, cmd.size, 3000)
        if (sent < 0) return null

        val resp = ByteArray(2048)
        val read = conn.bulkTransfer(epIn, resp, resp.size, 3000)
        if (read < 10) return null

        val dataLen = (resp[1].toInt() and 0xFF) or
                ((resp[2].toInt() and 0xFF) shl 8) or
                ((resp[3].toInt() and 0xFF) shl 16) or
                ((resp[4].toInt() and 0xFF) shl 24)

        val actualLen = minOf(dataLen, read - 10)
        if (actualLen <= 0) return ByteArray(0)

        return resp.copyOfRange(10, 10 + actualLen)
    }

    /**
     * ส่งคำสั่ง APDU และจัดการเคส ISO-7816 SW1=0x61 (Get Response) อัตโนมัติ
     */
    fun sendApdu(apdu: ByteArray): ByteArray? {
        var res = xfrBlock(apdu) ?: return null

        if (res.size >= 2) {
            val sw1 = res[res.size - 2].toInt() and 0xFF
            val sw2 = res[res.size - 1].toInt() and 0xFF
            if (sw1 == 0x61) {
                // มีข้อมูลรอให้ดึงขนาด sw2 bytes
                val getResponseCmd = byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, sw2.toByte())
                val getResp = xfrBlock(getResponseCmd)
                if (getResp != null && getResp.size > 2) {
                    res = getResp
                }
            }
        }
        return res
    }
}
