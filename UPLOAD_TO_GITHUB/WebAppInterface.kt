package com.vms.smartcard

import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * JavaScript Interface สำหรับให้หน้าเว็บ Google Apps Script สื่อสารกับ Native Android
 */
class WebAppInterface(private val activity: MainActivity) {

    companion object {
        @Volatile var latestCardResult: String? = null
        @Volatile var latestStatus: String = "waiting"
        @Volatile var latestMessage: String = "กรุณาเสียบเครื่องอ่าน Type-C"

        fun updateStatus(status: String, message: String) {
            latestStatus = status
            latestMessage = message
        }

        fun updateResult(json: String) {
            latestCardResult = json
        }
    }

    /**
     * ดึงผลลัพธ์การอ่านบัตรล่าสุด (สำหรับ JavaScript ฝั่ง iframe โพลลิ่งผลลัพธ์ ป้องกันปัญหา Cross-Origin)
     */
    @JavascriptInterface
    fun getLatestCardResult(): String? {
        val result = latestCardResult
        latestCardResult = null // เคลียร์เพื่อป้องกันการอ่านซ้ำ
        return result
    }

    /**
     * ดึงสถานะปัจจุบันของเครื่องอ่านและการอ่านข้อมูล
     */
    @JavascriptInterface
    fun getLatestStatus(): String {
        return "$latestStatus|$latestMessage"
    }

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
        return "1.1.0"
    }
}

