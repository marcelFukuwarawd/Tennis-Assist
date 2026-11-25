package com.courtvision.ai.processing

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Hardware video decoder that produces DOWNSCALED ARGB frames directly from the YUV output
 * buffers (no full-resolution bitmap copies), so 4K input can be analysed with little memory.
 */
class VideoFrameDecoder(private val context: Context, private val uri: Uri, private val targetWidth: Int) {

    class Frame(val ptsUs: Long, val index: Long, val argb: IntArray, val width: Int, val height: Int)

    var outWidth = 0; private set
    var outHeight = 0; private set
    private var buffer: IntArray = IntArray(0)

    /**
     * Decodes from [startUs]; [stride] = analyse one frame out of N. [onFrame] returns false to stop.
     * @return true if the end of stream was reached.
     */
    suspend fun decode(startUs: Long, stride: Int, shouldStop: () -> Boolean, onFrame: suspend (Frame) -> Boolean): Boolean {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; format = f; break }
        }
        if (track < 0 || format == null) { extractor.release(); throw IllegalStateException("no_video_track") }
        extractor.selectTrack(track)
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var decoded = 0L
        var stopped = false
        try {
            while (!outputDone && !stopped) {
                if (shouldStop()) { stopped = true; break }
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (info.size > 0 && info.presentationTimeUs >= startUs) {
                        if (decoded % stride == 0L) {
                            val image = codec.getOutputImage(outIdx)
                            if (image != null) {
                                val frame = convert(image, rotation, info.presentationTimeUs, decoded)
                                image.close()
                                if (!onFrame(frame)) stopped = true
                            }
                        }
                        decoded++
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return outputDone && !stopped
    }

    private fun convert(image: Image, rotation: Int, ptsUs: Long, index: Long): Frame {
        val crop = image.cropRect
        val srcW = crop.width(); val srcH = crop.height()
        val scale = maxOf(1f, (if (rotation == 90 || rotation == 270) srcH else srcW) / targetWidth.toFloat())
        val dw = (srcW / scale).toInt(); val dh = (srcH / scale).toInt()
        val rotated = rotation == 90 || rotation == 270
        outWidth = if (rotated) dh else dw
        outHeight = if (rotated) dw else dh
        if (buffer.size != dw * dh) buffer = IntArray(dw * dh)
        val out = buffer
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        for (oy in 0 until dh) {
            val sy = crop.top + (oy * scale).toInt()
            for (ox in 0 until dw) {
                val sx = crop.left + (ox * scale).toInt()
                val y = (yB.get(sy * yRow + sx * yPix).toInt() and 0xFF) - 16
                val u = (uB.get((sy / 2) * uRow + (sx / 2) * uPix).toInt() and 0xFF) - 128
                val v = (vB.get((sy / 2) * vRow + (sx / 2) * vPix).toInt() and 0xFF) - 128
                val yy = 1192 * maxOf(y, 0)
                val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                val px = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                val idx = when (rotation) {
                    90 -> ox * dh + (dh - 1 - oy)
                    180 -> (dh - 1 - oy) * dw + (dw - 1 - ox)
                    270 -> (dw - 1 - ox) * dh + oy
                    else -> oy * dw + ox
                }
                out[idx] = px
            }
        }
        return Frame(ptsUs, index, out, outWidth, outHeight)
    }
}
