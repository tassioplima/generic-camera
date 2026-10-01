package com.tassiolima.rawcam.camera

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

/** Saves photos and videos into the shared Pictures/RawCam and Movies/RawCam albums. */
object MediaStoreSaver {

    private const val DNG_MIME = "image/x-adobe-dng"
    private val PICTURES_PATH = Environment.DIRECTORY_PICTURES + "/RawCam/"
    private val MOVIES_PATH = Environment.DIRECTORY_MOVIES + "/RawCam/"

    fun saveImage(context: Context, bytes: ByteArray, displayName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RawCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore entry for image")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open output for $uri")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** Copies a finished recording into the gallery. Blocking - call off the main thread. */
    fun saveVideo(context: Context, sourceFile: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RawCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore entry for video")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input: InputStream -> input.copyTo(out) }
            } ?: error("Could not open output for $uri")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            sourceFile.delete()
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /**
     * Deletes a capture, plus its companion .dng when it's a JPEG shot with RAW on - both are
     * saved under the same base name, and a DNG orphaned from its JPEG is invisible in this
     * app's gallery but keeps eating storage.
     */
    fun delete(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val displayName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        resolver.delete(uri, null, null)

        if (displayName != null && displayName.endsWith(".jpg", ignoreCase = true)) {
            val dngName = displayName.substringBeforeLast('.') + ".dng"
            runCatching {
                resolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(PICTURES_PATH, dngName),
                )
            }
        }
    }

    /**
     * The newest photo or video this app has saved, so the thumbnail bubble can reappear
     * after the process is killed and relaunched (lastCapture only lives in memory).
     */
    fun findMostRecentCapture(context: Context): CapturedMedia? =
        listRecentCaptures(context, limit = 1).firstOrNull()

    /**
     * The most recent photos/videos this app has saved, newest first, for the swipeable
     * gallery. DNGs are left out: they're the RAW twin of a JPEG that's already listed, most
     * viewers can't render them, and sharing them as "image/jpeg" would be wrong anyway.
     */
    fun listRecentCaptures(context: Context, limit: Int = 60): List<CapturedMedia> {
        val resolver = context.contentResolver
        val images = queryRecentList(
            resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            PICTURES_PATH, isVideo = false, limit = limit,
        )
        val videos = queryRecentList(
            resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MOVIES_PATH, isVideo = true, limit = limit,
        )
        return (images + videos).sortedByDescending { it.timestampMs }.take(limit)
    }

    private fun queryRecentList(
        resolver: ContentResolver,
        collection: Uri,
        relativePath: String,
        isVideo: Boolean,
        limit: Int,
    ): List<CapturedMedia> {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        val results = mutableListOf<CapturedMedia>()
        resolver.query(
            collection, projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.MIME_TYPE}!=?",
            arrayOf(relativePath, DNG_MIME),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            // Some OEM MediaProvider implementations reject a raw "LIMIT" clause appended to
            // sortOrder (throws IllegalArgumentException), so cap it here instead.
            while (results.size < limit && cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                results += CapturedMedia(uri, isVideo, cursor.getLong(dateCol) * 1000L)
            }
        }
        return results
    }
}
