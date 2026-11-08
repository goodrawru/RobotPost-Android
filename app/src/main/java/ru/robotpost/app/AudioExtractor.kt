package ru.robotpost.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Extracts AAC track into M4A, without lossy re-encoding. */
object AudioExtractor {
    fun extract(source: File, target: File): File {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            } ?: error("В MP4 отсутствует AAC-аудиодорожка")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                val outputTrack = muxer.addTrack(format)
                muxer.start()
                started = true
                val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024)
                else 4 * 1024 * 1024
                val buffer = ByteBuffer.allocateDirect(size)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    buffer.clear()
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) break
                    info.offset = 0
                    info.size = read
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(outputTrack, buffer, info)
                    extractor.advance()
                }
            } finally {
                if (started) muxer.stop()
                muxer.release()
            }
            require(target.length() > 0) { "Аудиофайл пустой" }
            return target
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally { extractor.release() }
    }
}
