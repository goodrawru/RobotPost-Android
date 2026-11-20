package ru.robotpost.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/** Muxes compatible H.264 MP4 video and AAC M4A audio without transcoding. */
object Mp4Muxer {
    fun combine(
        video: File,
        audio: File,
        destination: File,
        checkCancelled: () -> Unit = {},
        onProgress: (Int) -> Unit = {},
        onFinalizing: () -> Unit = {}
    ): File {
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
            try {
                val v = muxer.addTrack(videoFormat)
                val a = muxer.addTrack(audioFormat)
                muxer.start()
                writeInterleaved(videoExtractor, audioExtractor, muxer, v, a,
                    video.length() + audio.length(),
                    maxOf(4 * 1024 * 1024, videoFormat.maxInputSize(), audioFormat.maxInputSize()),
                    checkCancelled, onProgress)
                checkCancelled()
                onFinalizing()
                muxer.stop()
            } catch (e: Exception) {
                runCatching { muxer.release() }
                throw e
            }
            muxer.release()
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

    private fun MediaFormat.maxInputSize(): Int =
        if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0

    private fun writeInterleaved(
        video: MediaExtractor,
        audio: MediaExtractor,
        muxer: MediaMuxer,
        videoTrack: Int,
        audioTrack: Int,
        totalBytes: Long,
        bufferSize: Int,
        checkCancelled: () -> Unit,
        onProgress: (Int) -> Unit
    ) {
        var buffer = ByteBuffer.allocateDirect(bufferSize)
        val info = MediaCodec.BufferInfo()
        var written = 0L
        var lastPercent = 0
        var videoTime = video.sampleTime
        var audioTime = audio.sampleTime
        while (videoTime >= 0 || audioTime >= 0) {
            checkCancelled()
            val useVideo = audioTime < 0 || (videoTime >= 0 && videoTime <= audioTime)
            val source = if (useVideo) video else audio
            val size = if (Build.VERSION.SDK_INT >= 28) source.sampleSize else null
            if (size != null) {
                require(size in 0..Int.MAX_VALUE.toLong()) { "Некорректный размер медиасэмпла" }
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
            }
            buffer.clear()
            val count = source.readSampleData(buffer, 0)
            check(count >= 0 && (size == null || count.toLong() == size)) { "Не удалось прочитать медиадорожку" }
            info.set(0, count, if (useVideo) videoTime else audioTime, source.sampleFlags)
            muxer.writeSampleData(if (useVideo) videoTrack else audioTrack, buffer, info)
            val nextTime = if (source.advance()) source.sampleTime else -1L
            if (useVideo) videoTime = nextTime else audioTime = nextTime
            written += count
            if (totalBytes > 0) {
                val percent = (written * 100.0 / totalBytes).toInt().coerceIn(0, 99)
                if (percent > lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }
    }
}
