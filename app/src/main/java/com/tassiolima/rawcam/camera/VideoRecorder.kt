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

    fun create(
        context: Context,
        outputFile: File,
        videoOption: VideoSizeOption,
        fps: Int,
        orientationHintDegrees: Int,
    ): MediaRecorder {
        val recorder = MediaRecorder(context)
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setVideoSize(videoOption.size.width, videoOption.size.height)
        recorder.setVideoFrameRate(fps)
        recorder.setVideoEncodingBitRate(bitRateFor(videoOption, fps))
        recorder.setAudioEncodingBitRate(128_000)
        recorder.setAudioSamplingRate(44_100)
        recorder.setOrientationHint(orientationHintDegrees)
        recorder.setOutputFile(outputFile.absolutePath)
        recorder.prepare()
        return recorder
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
