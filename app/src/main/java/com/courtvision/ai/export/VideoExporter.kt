package com.courtvision.ai.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.courtvision.ai.data.SegmentEntity
import com.courtvision.ai.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

enum class AspectFormat(val ratio: Float, val label: String) { LANDSCAPE(16f / 9f, "16:9"), PORTRAIT(9f / 16f, "9:16"), SQUARE(1f, "1:1"), FOUR_FIVE(4f / 5f, "4:5") }

/** One clip to render: session-global times plus the overlay text lines for it. */
data class RenderClip(val startMs: Long, val endMs: Long, val speed: Float, val overlay: List<String>)

/**
 * Non-destructive export with Media3 Transformer: the original files are only read.
 * Clips crossing a segment boundary are split into one item per segment.
 */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {

    private fun items(segments: List<SegmentEntity>, clip: RenderClip, format: AspectFormat): List<EditedMediaItem> {
        val out = ArrayList<EditedMediaItem>()
        for (seg in segments.filter { it.valid }) {
            val segEnd = seg.startOffsetMs + seg.durationMs
            val a = maxOf(clip.startMs, seg.startOffsetMs); val b = minOf(clip.endMs, segEnd)
            if (b - a < 100) continue
            val mi = MediaItem.Builder().setUri(Uri.parse(seg.uri)).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a - seg.startOffsetMs).setEndPositionMs(b - seg.startOffsetMs).build(),
            ).build()
            val fx = ArrayList<Effect>()
            fx += Presentation.createForAspectRatio(format.ratio, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            if (clip.speed != 1f) fx += SpeedChangeEffect(clip.speed)
            if (clip.overlay.isNotEmpty()) {
                val settings = OverlaySettings.Builder().setBackgroundFrameAnchor(-0.92f, 0.88f).setOverlayFrameAnchor(-1f, 1f).build()
                fx += OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(SpannableString(clip.overlay.joinToString("   ")), settings)))
            }
            out += EditedMediaItem.Builder(mi).setRemoveAudio(clip.speed != 1f).setEffects(Effects(emptyList(), fx)).build()
        }
        return out
    }

    /** Renders and returns the output file; [progress] is not available for every device. */
    suspend fun export(segments: List<SegmentEntity>, clips: List<RenderClip>, format: AspectFormat, name: String): Result<File> {
        val items = clips.flatMap { items(segments, it, format) }
        if (items.isEmpty()) return Result.failure(IllegalArgumentException("empty"))
        val out = File(Storage.exportsDir(context), "$name.mp4")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                // audio and silent items cannot be mixed in one sequence: drop audio if any clip is slowed
                val noAudio = clips.any { it.speed != 1f }
                val fixed = if (noAudio) items.map { it.buildUpon().setRemoveAudio(true).build() } else items
                val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Result.success(out)) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resume(Result.failure(exportException))
                    }
                }).build()
                transformer.start(Composition.Builder(EditedMediaItemSequence(fixed)).build(), out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }
    }

    /** Copies an exported file to the public Movies/CourtVisionAI folder (user-visible gallery). */
    suspend fun publish(file: File): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CourtVisionAI")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        context.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        uri
    }
}
