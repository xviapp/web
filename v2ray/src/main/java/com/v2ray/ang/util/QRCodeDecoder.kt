package com.v2ray.ang.util

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Draws share QR codes. Scanning was removed with the camera dependency. */
object QRCodeDecoder {
    /**
     * Creates a QR code bitmap from the given text.
     *
     * @param text The text to encode in the QR code.
     * @param size The size of the QR code bitmap.
     * @return The generated QR code bitmap, or null if an error occurs.
     */
    fun createQRCode(text: String, size: Int = 800): Bitmap? {
        return runCatching {
            val hints = mapOf(EncodeHintType.CHARACTER_SET to Charsets.UTF_8)
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size) { i ->
                if (bitMatrix.get(i % size, i / size)) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        }.getOrNull()
    }
}
