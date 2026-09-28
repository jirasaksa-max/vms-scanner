package com.vms.smartcard

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * ตัวประมวลผลคำสั่ง APDU และถอดรหัสข้อมูลบัตรประจำตัวประชาชนไทย (Thai National ID Card)
 * มาตรฐานกระทรวงมหาดไทย (ISO 7816-4)
 */
class ThaiIdCardParser(private val reader: CcidCardReader) {

    companion object {
        private const val TAG = "ThaiIdCardParser"
        private val TIS620: Charset = try {
            Charset.forName("TIS-620")
        } catch (e: Exception) {
            Charset.forName("windows-874")
        }

        // Applet AID ของกรมการปกครอง กระทรวงมหาดไทย
        private val SELECT_MOI_APPLET = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x08.toByte(),
            0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x54.toByte(),
            0x48.toByte(), 0x00.toByte(), 0x01.toByte()
        )
    }

    /**
     * ดึงข้อมูลทั้งหมดจากบัตรประชาชน (รวมรูปถ่าย JPEG)
     * รองรับ callback แสดงสถานะแบบเรียลไทม์
     */
    fun readFullCard(includePhoto: Boolean = true, onProgress: ((String) -> Unit)? = null): String {
        val root = JSONObject()
        try {
            // 1. Power On ชิปการ์ด
            onProgress?.invoke("⚡ (1/4) กำลังจ่ายไฟเข้าชิปการ์ด...")
            val atr = reader.powerOn()
            if (atr == null) {
                root.put("error", "ไม่สามารถจ่ายไฟให้ชิปการ์ดได้ (กรุณาหันด้านชิปทองเหลืองตามลูกศรและดันเข้าให้สุดล็อก)")
                return root.toString()
            }
            Log.d(TAG, "Card Powered On, ATR: ${atr.joinToString("") { "%02X".format(it) }}")

            // 2. Select MOI Application
            onProgress?.invoke("⚡ (2/4) กำลังเชื่อมต่อระบบบัตรประชาชน...")
            val selRes = reader.sendApdu(SELECT_MOI_APPLET)
            if (selRes == null || !isSuccess(selRes)) {
                val swStr = if (selRes != null && selRes.size >= 2) {
                    "SW=%02X%02X".format(selRes[selRes.size - 2], selRes[selRes.size - 1])
                } else "ไม่ได้รับการตอบรับจากชิป"
                root.put("error", "ไม่สามารถเลือกแอปพลิเคชันบัตรประชาชนได้ ($swStr) กรุณาตรวจสอบว่าหันด้านชิปทองเหลืองถูกต้อง")
                return root.toString()
            }

            // 3. อ่านเลขประจำตัวประชาชน 13 หลัก (Offset 0x0004, Length 13)
            onProgress?.invoke("⚡ (3/4) กำลังอ่านข้อมูลประจำตัวและที่อยู่...")
            val cidBytes = readBinary(0x00, 0x04, 13)
            val cid = cidBytes?.let { String(it, Charsets.US_ASCII).trim() } ?: ""
            if (cid.isEmpty()) {
                root.put("error", "ไม่สามารถดึงเลขประจำตัวประชาชนได้ กรุณาลองเสียบบัตรใหม่อีกครั้ง")
                return root.toString()
            }
            root.put("cid", cid)

            // 4. อ่านชื่อ-นามสกุล ภาษาไทย (Offset 0x0011, Length 100)
            val nameThBytes = readBinary(0x00, 0x11, 100)
            if (nameThBytes != null) {
                val fullStr = String(nameThBytes, TIS620).trim()
                val parts = fullStr.split("#")
                val title = parts.getOrNull(0)?.trim() ?: ""
                val firstName = parts.getOrNull(1)?.trim() ?: ""
                val middleName = parts.getOrNull(2)?.trim() ?: ""
                val lastName = parts.getOrNull(3)?.trim() ?: ""

                root.put("titleTh", title)
                root.put("firstNameTh", if (middleName.isNotEmpty()) "$firstName $middleName" else firstName)
                root.put("lastNameTh", lastName)
            }

            // 5. อ่านชื่อ-นามสกุล ภาษาอังกฤษ (Offset 0x0075, Length 100)
            val nameEnBytes = readBinary(0x00, 0x75, 100)
            if (nameEnBytes != null) {
                val fullStr = String(nameEnBytes, Charsets.US_ASCII).trim()
                val parts = fullStr.split("#")
                val title = parts.getOrNull(0)?.trim() ?: ""
                val firstName = parts.getOrNull(1)?.trim() ?: ""
                val middleName = parts.getOrNull(2)?.trim() ?: ""
                val lastName = parts.getOrNull(3)?.trim() ?: ""

                root.put("titleEn", title)
                root.put("firstNameEn", if (middleName.isNotEmpty()) "$firstName $middleName" else firstName)
                root.put("lastNameEn", lastName)
            }

            // 6. อ่านวันเกิด (Offset 0x00D9, Length 8 -> YYYYMMDD พ.ศ.)
            val dobBytes = readBinary(0x00, 0xD9, 8)
            if (dobBytes != null) {
                val dobRaw = String(dobBytes, Charsets.US_ASCII).trim()
                root.put("dob", formatThaiDate(dobRaw))
            }

            // 7. อ่านเพศ (Offset 0x00E1, Length 1 -> 1=ชาย, 2=หญิง)
            val genderBytes = readBinary(0x00, 0xE1, 1)
            if (genderBytes != null) {
                val g = String(genderBytes, Charsets.US_ASCII).trim()
                root.put("gender", if (g == "1") "ชาย" else if (g == "2") "หญิง" else "")
            }

            // 8. อ่านที่อยู่ตามทะเบียนบ้าน (Offset 0x1579, Length 100 + Offset 0x15DD, Length 60)
            val addrPart1 = readBinary(0x15, 0x79, 100) ?: ByteArray(0)
            val addrPart2 = readBinary(0x15, 0xDD, 60) ?: ByteArray(0)
            val combinedAddrBytes = ByteArray(addrPart1.size + addrPart2.size)
            System.arraycopy(addrPart1, 0, combinedAddrBytes, 0, addrPart1.size)
            System.arraycopy(addrPart2, 0, combinedAddrBytes, addrPart1.size, addrPart2.size)

            val rawAddr = String(combinedAddrBytes, TIS620).trim()
            root.put("address", formatThaiAddress(rawAddr))

            // 9. วันออกบัตร และ วันหมดอายุ
            val issueBytes = readBinary(0x01, 0x67, 8)
            if (issueBytes != null) {
                root.put("issueDate", formatThaiDate(String(issueBytes, Charsets.US_ASCII).trim()))
            }
            val expBytes = readBinary(0x01, 0x6F, 8)
            if (expBytes != null) {
                root.put("expireDate", formatThaiDate(String(expBytes, Charsets.US_ASCII).trim()))
            }

            // 10. อ่านรูปถ่ายหน้าตรงจากชิป
            if (includePhoto) {
                try {
                    onProgress?.invoke("⚡ (4/4) กำลังอ่านรูปถ่ายหน้าตรงจากชิป...")
                    val photoBase64 = readPhoto(onProgress)
                    if (photoBase64.isNotEmpty()) {
                        root.put("photoBase64", photoBase64)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Read photo error, proceeding with text data", e)
                }
            }

            root.put("success", true)

        } catch (e: Exception) {
            Log.e(TAG, "Card read exception", e)
            root.put("error", e.message ?: "เกิดข้อผิดพลาดในการติดต่อชิปการ์ด")
        }

        return root.toString()
    }

    /**
     * สั่งคำสั่ง Read Binary (0x80, 0xB0) ตาม Offset และความยาวที่กำหนด
     */
    private fun readBinary(p1: Int, p2: Int, len: Int): ByteArray? {
        val apdu = byteArrayOf(
            0x80.toByte(), 0xB0.toByte(),
            p1.toByte(), p2.toByte(),
            0x02.toByte(), 0x00.toByte(), len.toByte()
        )
        val res = reader.sendApdu(apdu) ?: return null
        if (res.size >= 2 && isSuccess(res)) {
            return res.copyOfRange(0, res.size - 2)
        }
        return null
    }

    /**
     * ดึงภาพถ่ายหน้าตรงจากชิปการ์ด
     */
    private fun readPhoto(onProgress: ((String) -> Unit)? = null): String {
        val bos = ByteArrayOutputStream()

        for (i in 1..20) {
            val p1 = i
            val p2 = (0x7C - i) and 0xFF
            var chunk = readBinary(p1, p2, 0xFF)
            if (chunk == null || chunk.isEmpty()) {
                chunk = readBinary(p1, p2, 0xFE)
            }
            if (chunk != null && chunk.isNotEmpty()) {
                bos.write(chunk)
                Log.d(TAG, "Photo chunk $i: ${chunk.size} bytes read")
            }
            if (i % 4 == 0 || i == 20) {
                onProgress?.invoke("⚡ (4/4) กำลังอ่านรูปถ่ายหน้าตรง... (${i * 5}%)")
            }
        }

        val allBytes = bos.toByteArray()
        if (allBytes.isEmpty()) {
            Log.w(TAG, "readPhoto: allBytes is empty")
            return ""
        }
        Log.d(TAG, "readPhoto: total ${allBytes.size} bytes gathered")

        var startIndex = -1
        var endIndex = -1

        for (i in 0 until allBytes.size - 1) {
            if (allBytes[i] == 0xFF.toByte() && allBytes[i + 1] == 0xD8.toByte()) {
                startIndex = i
                break
            }
        }

        if (startIndex != -1) {
            for (i in allBytes.size - 2 downTo startIndex) {
                if (allBytes[i] == 0xFF.toByte() && allBytes[i + 1] == 0xD9.toByte()) {
                    endIndex = i + 2
                    break
                }
            }
        }

        val validJpegBytes = if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            allBytes.copyOfRange(startIndex, endIndex)
        } else if (startIndex != -1) {
            allBytes.copyOfRange(startIndex, allBytes.size)
        } else {
            allBytes
        }

        return Base64.encodeToString(validJpegBytes, Base64.NO_WRAP)
    }

    /**
     * แปลงรูปแบบที่อยู่ภาษาไทยที่มีเครื่องหมาย # ให้กลายเป็นข้อความที่อยู่เรียบร้อย
     */
    private fun formatThaiAddress(raw: String): String {
        val parts = raw.split("#").map { it.trim() }
        val sb = StringBuilder()

        val houseNo = parts.getOrNull(0) ?: ""
        val moo = parts.getOrNull(1) ?: ""
        val trok = parts.getOrNull(2) ?: ""
        val soi = parts.getOrNull(3) ?: ""
        val road = parts.getOrNull(4) ?: ""
        val tambon = parts.getOrNull(5) ?: ""
        val amphoe = parts.getOrNull(6) ?: ""
        val province = parts.getOrNull(7) ?: ""

        if (houseNo.isNotEmpty()) sb.append(houseNo)
        if (moo.isNotEmpty()) sb.append(" หมู่ที่ ").append(moo.replace("หมู่ที่", "").replace("หมู่", "").trim())
        if (trok.isNotEmpty()) sb.append(" ตรอก").append(trok.replace("ตรอก", "").trim())
        if (soi.isNotEmpty()) sb.append(" ซอย").append(soi.replace("ซอย", "").trim())
        if (road.isNotEmpty()) sb.append(" ถนน").append(road.replace("ถนน", "").trim())

        val isBkk = province.contains("กรุงเทพ")
        if (tambon.isNotEmpty()) {
            val prefix = if (isBkk) "แขวง" else "ต."
            sb.append(" ").append(prefix).append(tambon.replace("ตำบล", "").replace("แขวง", "").replace("ต.", "").trim())
        }
        if (amphoe.isNotEmpty()) {
            val prefix = if (isBkk) "เขต" else "อ."
            sb.append(" ").append(prefix).append(amphoe.replace("อำเภอ", "").replace("เขต", "").replace("อ.", "").trim())
        }
        if (province.isNotEmpty()) {
            val prefix = if (isBkk) "" else "จ."
            sb.append(" ").append(prefix).append(province.replace("จังหวัด", "").replace("จ.", "").trim())
        }

        return sb.toString().trim()
    }

    private fun formatThaiDate(raw: String): String {
        if (raw.length == 8) {
            val year = raw.substring(0, 4)
            val month = raw.substring(4, 6)
            val day = raw.substring(6, 8)
            return "$day/$month/$year"
        }
        return raw
    }

    private fun isSuccess(res: ByteArray): Boolean {
        if (res.size < 2) return false
        val sw1 = res[res.size - 2].toInt() and 0xFF
        val sw2 = res[res.size - 1].toInt() and 0xFF
        return (sw1 == 0x90 && sw2 == 0x00) || sw1 == 0x61
    }
}
