package com.tassiolima.rawcam.camera

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** Wraps MediaRecorder setup for a chosen resolution/fps combo, recording to a temp file first. */
object VideoRecorder {

    fun tempOutputFile(context: Context): File {
        val dir = File(context.cacheDir, "video_tmp").apply { mkdirs() }
        return File(dir, "rawcam_${System.currentTimeMillis()}.mp4")
    }

    /**
     * Normal video. Tries stereo first and falls back to mono if this audio source can't do
     * two channels on this phone (prepare() rejects it), rather than failing the recording.
     */
    fun create(
        context: Context,
        outputFile: File,
        videoOption: VideoSizeOption,
        fps: Int,
        orientationHintDegrees: Int,
        audioMode: AudioMode,
        facingFront: Boolean,
    ): MediaRecorder {
        return try {
            build(context, outputFile, videoOption, fps, orientationHintDegrees, audioMode, facingFront, channels = 2)
        } catch (e: Exception) {
            android.util.Log.w("VideoRecorder", "Stereo audio rejected for $audioMode, retrying mono", e)
            outputFile.delete()
            build(context, outputFile, videoOption, fps, orientationHintDegrees, audioMode, facingFront, channels = 1)
        }
    }

    private fun build(
        context: Context,
        outputFile: File,
        videoOption: VideoSizeOption,
        fps: Int,
        orientationHintDegrees: Int,
        audioMode: AudioMode,
        facingFront: Boolean,
        channels: Int,
    ): MediaRecorder {
        val recorder = MediaRecorder(context)
        try {
            recorder.setAudioSource(audioSourceFor(context, audioMode))
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setVideoSize(videoOption.size.width, videoOption.size.height)
            recorder.setVideoFrameRate(fps)
            recorder.setVideoEncodingBitRate(bitRateFor(videoOption, fps))
            // 48kHz is the native rate of phone audio hardware (44.1k forces a resample), and
            // 128kbps for stereo AAC is audibly lossy - 256kbps stereo / 160kbps mono instead.
            recorder.setAudioChannels(channels)
            recorder.setAudioSamplingRate(48_000)
            recorder.setAudioEncodingBitRate(if (channels == 2) 256_000 else 160_000)
            // Steer beamforming/mic choice toward what the camera is pointing at: the subject
            // for the back camera, the person holding the phone for a selfie video.
            recorder.setPreferredMicrophoneDirection(
                if (facingFront) MediaRecorder.MIC_DIRECTION_TOWARDS_USER
                else MediaRecorder.MIC_DIRECTION_AWAY_FROM_USER,
            )
            recorder.setOrientationHint(orientationHintDegrees)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.prepare()
            return recorder
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
    }

    /**
     * UNPROCESSED is the truly raw mic signal, but it's optional hardware support - where it's
     * missing, VOICE_RECOGNITION is the closest thing (Android's CDD requires it to have AGC
     * and noise suppression off by default).
     */
    fun audioSourceFor(context: Context, mode: AudioMode): Int = when (mode) {
        AudioMode.CAMERA -> MediaRecorder.AudioSource.CAMCORDER
        AudioMode.RAW -> if (supportsUnprocessed(context)) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    fun supportsUnprocessed(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    /**
     * Timelapse: the camera keeps streaming at its normal rate, but MediaRecorder only keeps
     * one frame every [speed]/[TIMELAPSE_PLAYBACK_FPS] seconds (setCaptureRate) and stamps them
     * for playback at [TIMELAPSE_PLAYBACK_FPS] - so real time is compressed [speed] times. No
     * audio track: sped-up audio is just noise, and an audio source would also force the muxer
     * to wait on real-time audio timestamps.
     */
    fun createTimelapse(
        context: Context,
        outputFile: File,
        videoOption: VideoSizeOption,
        speed: Int,
        orientationHintDegrees: Int,
    ): MediaRecorder {
        val recorder = MediaRecorder(context)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(videoOption.size.width, videoOption.size.height)
        recorder.setVideoFrameRate(TIMELAPSE_PLAYBACK_FPS)
        recorder.setCaptureRate(TIMELAPSE_PLAYBACK_FPS.toDouble() / speed.coerceAtLeast(1))
        recorder.setVideoEncodingBitRate(bitRateFor(videoOption, TIMELAPSE_PLAYBACK_FPS))
        recorder.setOrientationHint(orientationHintDegrees)
        recorder.setOutputFile(outputFile.absolutePath)
        recorder.prepare()
        return recorder
    }

    const val TIMELAPSE_PLAYBACK_FPS = 30

    private fun bitRateFor(videoOption: VideoSizeOption, fps: Int): Int {
        val basePixels = videoOption.size.width.toLong() * videoOption.size.height
        val is4k = videoOption.size.height >= 2160
        val base = if (is4k) 45_000_000 else 12_000_000
        val fpsFactor = if (fps >= 60) 1.6 else 1.0
        return (base * fpsFactor).toInt().also { require(basePixels > 0) }
    }
}
