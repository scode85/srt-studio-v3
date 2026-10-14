package com.srtstudio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioExtractor {

    data class Info(val wavFile: File, val sampleRate: Int, val durationMs: Long)

    fun extractWav(ctx: Context, uri: Uri, outFile: File): Info? {
        val extractor = MediaExtractor()
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { extractor.setDataSource(it.fileDescriptor) }

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return null

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            outFile.parentFile?.mkdirs()
            if (outFile.exists()) outFile.delete()
            val raf = RandomAccessFile(outFile, "rw")
            raf.setLength(0)
            writeWavHeader(raf, srcRate, 1, 0)

            var totalSamples = 0L
            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(inBuf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                    val shortCount = bufInfo.size / 2
                    val shorts = ShortArray(shortCount)
                    outBuf.asShortBuffer().get(shorts)

                    val ch = if (srcChannels > 0) srcChannels else 1
                    val monoLen = shortCount / ch
                    val bytes = ByteArray(monoLen * 2)
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until monoLen) {
                        var sum = 0
                        for (c in 0 until ch) sum += shorts[i * ch + c].toInt()
                        bb.putShort((sum / ch).toShort())
                    }
                    raf.write(bytes)
                    totalSamples += monoLen

                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        outputDone = true
                }
            }

            codec.stop(); codec.release()

            val dataSize = totalSamples * 2
            raf.seek(4);  raf.write(le32(36 + dataSize))
            raf.seek(40); raf.write(le32(dataSize))
            raf.close()

            val durationMs = totalSamples * 1000 / srcRate
            return Info(outFile, srcRate, durationMs)
        } finally {
            extractor.release()
        }
    }

    fun splitWav(srcWav: File, outDir: File, sampleRate: Int, chunkSec: Int):
            List<Pair<File, Long>> {
        val bytesPerSec = sampleRate * 2L
        val chunkBytes = bytesPerSec * chunkSec
        val totalBytes = srcWav.length() - 44
        val chunks = ArrayList<Pair<File, Long>>()
        outDir.mkdirs()
        var offsetBytes = 0L
        var idx = 0
        val src = RandomAccessFile(srcWav, "r")
        try {
            while (offsetBytes < totalBytes) {
                val remain = totalBytes - offsetBytes
                val take = if (remain > chunkBytes) chunkBytes else remain
                val cf = File(outDir, "chunk_$idx.wav")
                val dst = RandomAccessFile(cf, "rw")
                dst.setLength(0)
                writeWavHeader(dst, sampleRate, 1, take.toInt())
                src.seek(44 + offsetBytes)
                val buf = ByteArray(64 * 1024)
                var copied = 0L
                while (copied < take) {
                    val toRead = minOf(buf.size.toLong(), take - copied).toInt()
                    val n = src.read(buf, 0, toRead)
                    if (n <= 0) break
                    dst.write(buf, 0, n)
                    copied += n
                }
                dst.close()
                val offsetMs = offsetBytes * 1000 / bytesPerSec
                chunks.add(cf to offsetMs)
                offsetBytes += take
                idx++
            }
        } finally { src.close() }
        return chunks
    }

    private fun writeWavHeader(raf: RandomAccessFile, rate: Int, ch: Int, dataSize: Int) {
        val byteRate = rate * ch * 2
        raf.seek(0)
        raf.write("RIFF".toByteArray(Charsets.US_ASCII))
        raf.write(le32(36 + dataSize))
        raf.write("WAVE".toByteArray(Charsets.US_ASCII))
        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        raf.write(le32(16))
        raf.write(le16(1))
        raf.write(le16(ch))
        raf.write(le32(rate))
        raf.write(le32(byteRate))
        raf.write(le16(ch * 2))
        raf.write(le16(16))
        raf.write("data".toByteArray(Charsets.US_ASCII))
        raf.write(le32(dataSize))
    }

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    private fun le16(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}
