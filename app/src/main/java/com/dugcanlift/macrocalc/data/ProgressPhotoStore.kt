package com.dugcanlift.macrocalc.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.dugcanlift.kit.DayKey
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Progress photos: kept as files in LIFT's own private storage, never in a JSON store, never in a
 * backup file, never sent with a coach link. A port of LIFT iPhone's `ProgressPhotoStore`, and
 * `ProgressPhotoStoreTest` is a port of its tests.
 *
 * Files rather than records because a photo is megabytes and the backup copies the stores whole;
 * a backup that quietly grew by a gigabyte of pictures is a backup nobody can move to a new phone.
 * The cost is said in the screen's own words: a photo stays on this phone.
 *
 * Picked with the system Photo Picker, which runs outside the app and needs no storage or media
 * permission: LIFT only ever sees the one picture chosen. `filesDir` is app-private and, on every
 * phone this app supports, encrypted with the device credential -- this platform's counterpart to
 * the iPhone's complete file protection.
 */
class ProgressPhotoStore(val directory: File) {

    data class Photo(val file: File, val dayKey: String) {
        val id: String get() = file.name
        val date: LocalDate get() = DayKey.parse(dayKey) ?: LocalDate.MIN
    }

    /**
     * Oldest first. A file whose name does not start with a day is not one of these and is left
     * alone, as is anything not ending ".jpg" (a write still in progress ends ".tmp").
     */
    fun photos(): List<Photo> {
        val names = directory.list()?.toList().orEmpty()
        return names.mapNotNull { name ->
            if (!name.endsWith(".jpg") || name.length <= 10) return@mapNotNull null
            val key = name.take(10)
            if (DayKey.parse(key) == null) return@mapNotNull null
            Photo(File(directory, name), key)
        }.sortedWith(compareBy<Photo> { it.dayKey }.thenBy { it.id })
    }

    /**
     * Re-encoded as a JPEG no longer than [LONGEST] px on its long side, which is plenty to compare
     * two photos and a fraction of a camera original. The re-encode also leaves the original's
     * metadata behind, location included: [Bitmap.compress] writes none. The original's EXIF
     * orientation is applied to the pixels first, so a portrait shot stays upright without it.
     *
     * @throws IOException for anything that is not a picture.
     */
    fun save(bytes: ByteArray, day: LocalDate = LocalDate.now()): Photo {
        val bitmap = decode(bytes) ?: throw IOException("not a picture")
        val jpeg = try {
            java.io.ByteArrayOutputStream().also { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) throw IOException("could not encode")
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("could not make ${directory.name}")
        val key = day.toString()
        val name = "${key}_${UUID.randomUUID()}.jpg"
        val temp = File(directory, "$name.tmp")
        FileOutputStream(temp).use { it.write(jpeg); it.fd.sync() }
        val file = File(directory, name)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("could not save")
        }
        return Photo(file, key)
    }

    fun delete(photo: Photo) {
        photo.file.delete()
    }

    companion object {
        const val LONGEST = 2048
        private const val QUALITY = 80

        fun forApp(context: Context): ProgressPhotoStore =
            ProgressPhotoStore(File(context.applicationContext.filesDir, "ProgressPhotos"))

        /** The size [longest] allows, keeping the shape: never larger than it came. */
        fun scaledSize(width: Int, height: Int, longest: Int = LONGEST): Pair<Int, Int> {
            val scale = min(1.0, longest.toDouble() / max(max(width, height), 1))
            return Pair(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
        }

        /**
         * The largest power of two that still leaves the long side at least [target], so a 12 MP
         * original is never decoded whole only to be thrown away.
         */
        fun sampleSize(width: Int, height: Int, target: Int): Int {
            var sample = 1
            while (max(width, height) / (sample * 2) >= target) sample *= 2
            return sample
        }

        /**
         * The picture, upright and at most [longest] px on its long side, or null for bytes that
         * are not one. Also how the screens read a stored photo, at a thumbnail's size.
         */
        fun decode(bytes: ByteArray, longest: Int = LONGEST): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, longest)
            }
            val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val (w, h) = scaledSize(sampled.width, sampled.height, longest)
            val scaled = if (w == sampled.width && h == sampled.height) sampled
            else Bitmap.createScaledBitmap(sampled, w, h, true).also { if (it !== sampled) sampled.recycle() }
            return upright(scaled, orientation(bytes))
        }

        fun decode(file: File, longest: Int): Bitmap? =
            runCatching { decode(file.readBytes(), longest) }.getOrNull()

        private fun orientation(bytes: ByteArray): Int = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                else -> return bitmap
            }
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (turned !== bitmap) bitmap.recycle()
            return turned
        }
    }
}
