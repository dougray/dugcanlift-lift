package com.dugcanlift.macrocalc.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * A port of LIFT iPhone's `ProgressPhotoStoreTests`, case for case, plus the one thing the
 * iPhone gets from UIKit for free: a re-encoded photo carries no metadata. Native graphics, so the
 * pictures are really decoded and encoded.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressPhotoStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private fun store() = ProgressPhotoStore(File(folder.root, "ProgressPhotos"))

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.RED)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    @Test fun `photos are kept oldest first and deleted`() {
        val store = store()
        assertEquals(emptyList<ProgressPhotoStore.Photo>(), store.photos())
        val later = store.save(jpeg(30, 40), LocalDate.of(2027, 1, 15))
        val earlier = store.save(jpeg(30, 40), LocalDate.of(2026, 9, 21))
        assertEquals(listOf(earlier.id, later.id), store.photos().map { it.id })
        store.delete(earlier)
        assertEquals(listOf(later.id), store.photos().map { it.id })
    }

    /** A camera original is cut down to 2048 px on its long side. */
    @Test fun `a big photo is scaled down`() {
        val photo = store().save(jpeg(4032, 3024))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.file.path, bounds)
        assertEquals(2048, maxOf(bounds.outWidth, bounds.outHeight))
        assertEquals(1536, minOf(bounds.outWidth, bounds.outHeight))
    }

    @Test fun `something that is not a picture is refused`() {
        try {
            store().save("not a picture".toByteArray())
            fail("expected a refusal")
        } catch (expected: IOException) {
        }
    }

    /** Anything else in the folder is not a progress photo and is left alone. */
    @Test fun `other files are ignored`() {
        val store = store()
        store.directory.mkdirs()
        File(store.directory, "notes.txt").writeBytes(ByteArray(0))
        File(store.directory, "nonsense.jpg").writeBytes(ByteArray(0))
        File(store.directory, "2026-10-28_half-written.jpg.tmp").writeBytes(ByteArray(0))
        assertEquals(emptyList<ProgressPhotoStore.Photo>(), store.photos())
        assertTrue(File(store.directory, "notes.txt").exists())
    }

    /** Nothing of the original's EXIF -- its location above all -- survives the re-encode. */
    @Test fun `the saved file carries no exif`() {
        val original = File(folder.root, "located.jpg").apply { writeBytes(jpeg(64, 48)) }
        android.media.ExifInterface(original.path).apply {
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,29/1")
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE, "122/1,25/1,9/1")
            setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            saveAttributes()
        }
        fun hasExif(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1).contains("Exif\u0000\u0000")
        assertTrue("the original carries a location", hasExif(original.readBytes()))
        val photo = store().save(original.readBytes())
        assertFalse("an APP1 Exif segment", hasExif(photo.file.readBytes()))
    }

    /** A portrait shot stored sideways with an orientation tag is saved upright, the tag gone. */
    @Test fun `the orientation tag is applied to the pixels`() {
        val original = File(folder.root, "sideways.jpg").apply { writeBytes(jpeg(40, 30)) }
        android.media.ExifInterface(original.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val photo = store().save(original.readBytes())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.file.path, bounds)
        assertEquals(Pair(30, 40), Pair(bounds.outWidth, bounds.outHeight))
    }

    @Test fun `the sizes are the iPhone's`() {
        assertEquals(Pair(2048, 1536), ProgressPhotoStore.scaledSize(4032, 3024))
        assertEquals(Pair(30, 40), ProgressPhotoStore.scaledSize(30, 40))
        assertEquals(1, ProgressPhotoStore.sampleSize(3000, 2000, 2048))
        assertEquals(2, ProgressPhotoStore.sampleSize(4096, 3072, 2048))
        assertEquals(4, ProgressPhotoStore.sampleSize(9000, 6000, 2048))
    }
}
