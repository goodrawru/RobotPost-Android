package ru.robotpost.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Muxes compatible H.264 MP4 video and AAC M4A audio without transcoding. */
object Mp4Muxer {
    fun combine(video: File, audio: File, destination: File): File {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val videoTrack = (0 until videoExtractor.trackCount).firstOrNull {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("Видеодорожка не найдена")
            val audioTrack = (0 until audioExtractor.trackCount).firstOrNull {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Аудиодорожка не найдена")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            require(videoFormat.getString(MediaFormat.KEY_MIME) == "video/avc") { "Нужен H.264 поток" }
            require(audioFormat.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm") { "Нужен AAC поток" }
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                val v = muxer.addTrack(videoFormat)
                val a = muxer.addTrack(audioFormat)
                muxer.start()
                started = true
                writeTrack(videoExtractor, muxer, v, videoFormat)
                writeTrack(audioExtractor, muxer, a, audioFormat)
            } finally {
                if (started) muxer.stop()
                muxer.release()
            }
            require(destination.exists() && destination.length() > 0) { "MP4 пустой" }
            return destination
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun writeTrack(extractor: MediaExtractor, muxer: MediaMuxer, track: Int, format: MediaFormat) {
        val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024)
        else 4 * 1024 * 1024
        val buffer = ByteBuffer.allocateDirect(bufferSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val count = extractor.readSampleData(buffer, 0)
            if (count < 0) break
            info.offset = 0
            info.size = count
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }
}
