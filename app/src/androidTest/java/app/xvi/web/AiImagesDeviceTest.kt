package app.xvi.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiImagesDeviceTest {
    @Test fun transparentPicturesUseAReadableWhiteBackground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(File(context.cacheDir, "uploads").apply { mkdirs() }, "ai-transparent-test.png")
        try {
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val image = AiImages.read(context.contentResolver, uri, "透明图片")!!
            val bytes = Base64.decode(image.url.substringAfter(','), Base64.NO_WRAP)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertTrue(android.graphics.Color.red(decoded.getPixel(50, 50)) > 245)
            decoded.recycle()
        } finally { file.delete() }
    }

    @Test fun albumPicturesAreResizedOrientedAndStrippedOfExif() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "uploads").apply { mkdirs() }
        val file = File(folder, "ai-image-test.jpg")
        try {
            val bitmap = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_ARTIST, "private metadata")
                saveAttributes()
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val image = AiImages.read(context.contentResolver, uri, "相册图片")!!
            val bytes = Base64.decode(image.url.substringAfter(','), Base64.NO_WRAP)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(640, decoded.width)
            assertEquals(1280, decoded.height)
            decoded.recycle()
            assertTrue(bytes.size <= AiImages.MAX_BYTES)
            assertNotNull(image.preview)
            val exif = bytes.inputStream().use { ExifInterface(it) }
            assertNull(exif.getAttribute(ExifInterface.TAG_ARTIST))
            assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        } finally { file.delete() }
    }
}
