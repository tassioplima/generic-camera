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
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

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
        resolver.openOutputStream(uri)?.use { out ->
            sourceFile.inputStream().use { input: InputStream -> input.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        sourceFile.delete()
        return uri
    }

    fun delete(context: Context, uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }

    /**
     * The newest photo or video this app has saved, so the thumbnail bubble can reappear
     * after the process is killed and relaunched (lastCapture only lives in memory).
     */
    fun findMostRecentCapture(context: Context): CapturedMedia? {
        val resolver = context.contentResolver
        val image = queryMostRecent(
            resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_PICTURES + "/RawCam/", isVideo = false,
        )
        val video = queryMostRecent(
            resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_MOVIES + "/RawCam/", isVideo = true,
        )
        return listOfNotNull(image, video).maxByOrNull { it.timestampMs }
    }

    /** The most recent photos/videos this app has saved, newest first, for the swipeable gallery. */
    fun listRecentCaptures(context: Context, limit: Int = 60): List<CapturedMedia> {
        val resolver = context.contentResolver
        val images = queryRecentList(
            resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_PICTURES + "/RawCam/", isVideo = false, limit = limit,
        )
        val videos = queryRecentList(
            resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_MOVIES + "/RawCam/", isVideo = true, limit = limit,
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
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(relativePath),
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

    private fun queryMostRecent(
        resolver: ContentResolver,
        collection: Uri,
        relativePath: String,
        isVideo: Boolean,
    ): CapturedMedia? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        resolver.query(
            collection, projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(relativePath),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val dateAddedSec = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                val uri = ContentUris.withAppendedId(collection, id)
                return CapturedMedia(uri, isVideo, dateAddedSec * 1000L)
            }
        }
        return null
    }
}
