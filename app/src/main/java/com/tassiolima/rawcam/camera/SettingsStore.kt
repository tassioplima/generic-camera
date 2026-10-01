package com.tassiolima.rawcam.camera

import android.content.Context

/**
 * User preferences that should survive the app being killed/relaunched, only reset by
 * "clear storage" or uninstalling - plain SharedPreferences already has exactly that lifetime.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("rawcam_settings", Context.MODE_PRIVATE)

    var photoAspectRatio: PhotoAspectRatio
        get() = PhotoAspectRatio.entries.firstOrNull { it.name == prefs.getString(KEY_ASPECT, null) }
            ?: PhotoAspectRatio.RATIO_16_9
        set(value) = prefs.edit().putString(KEY_ASPECT, value.name).apply()

    var gridEnabled: Boolean
        get() = prefs.getBoolean(KEY_GRID, false)
        set(value) = prefs.edit().putBoolean(KEY_GRID, value).apply()

    var rawEnabled: Boolean
        get() = prefs.getBoolean(KEY_RAW, false)
        set(value) = prefs.edit().putBoolean(KEY_RAW, value).apply()

    var shutterSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUTTER_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SHUTTER_SOUND, value).apply()

    var shutterFlashEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUTTER_FLASH, true)
        set(value) = prefs.edit().putBoolean(KEY_SHUTTER_FLASH, value).apply()

    var videoWidth: Int
        get() = prefs.getInt(KEY_VIDEO_W, 1920)
        set(value) = prefs.edit().putInt(KEY_VIDEO_W, value).apply()

    var videoHeight: Int
        get() = prefs.getInt(KEY_VIDEO_H, 1080)
        set(value) = prefs.edit().putInt(KEY_VIDEO_H, value).apply()

    var videoFps: Int
        get() = prefs.getInt(KEY_VIDEO_FPS, 30)
        set(value) = prefs.edit().putInt(KEY_VIDEO_FPS, value).apply()

    var nightVideoEnabled: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_VIDEO, false)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT_VIDEO, value).apply()

    var timelapseSpeed: Int
        get() = prefs.getInt(KEY_TIMELAPSE_SPEED, 10)
        set(value) = prefs.edit().putInt(KEY_TIMELAPSE_SPEED, value).apply()

    fun saveVideoSettings(width: Int, height: Int, fps: Int) {
        prefs.edit()
            .putInt(KEY_VIDEO_W, width)
            .putInt(KEY_VIDEO_H, height)
            .putInt(KEY_VIDEO_FPS, fps)
            .apply()
    }

    private companion object {
        const val KEY_ASPECT = "photo_aspect_ratio"
        const val KEY_GRID = "grid_enabled"
        const val KEY_RAW = "raw_enabled"
        const val KEY_SHUTTER_SOUND = "shutter_sound_enabled"
        const val KEY_SHUTTER_FLASH = "shutter_flash_enabled"
        const val KEY_VIDEO_W = "video_width"
        const val KEY_VIDEO_H = "video_height"
        const val KEY_VIDEO_FPS = "video_fps"
        const val KEY_NIGHT_VIDEO = "night_video_enabled"
        const val KEY_TIMELAPSE_SPEED = "timelapse_speed"
    }
}
