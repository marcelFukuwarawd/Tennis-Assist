package com.courtvision.ai.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.courtvision.ai.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SessionRepository(private val context: Context, val db: AppDatabase) {

    fun sessionDir(id: Long): File = File(Storage.moviesRoot(context), "session_$id").apply { mkdirs() }

    suspend fun createSession(s: SessionEntity): Long = db.sessions().insert(s)

    /** Reads duration / size / fps of a video. Returns null when the file is unreadable (corrupted). */
    suspend fun probe(uri: Uri): VideoInfo? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withContext null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            val fps = if (frames != null && dur > 0) frames * 1000f / dur else 0f
            VideoInfo(dur, w, h, fps)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    data class VideoInfo(val durationMs: Long, val width: Int, val height: Int, val fps: Float)

    suspend fun addSegment(sessionId: Long, uri: Uri, info: VideoInfo?): SegmentEntity {
        val existing = db.segments().forSession(sessionId)
        val offset = existing.filter { it.valid }.sumOf { it.durationMs }
        val seg = SegmentEntity(
            sessionId = sessionId, index = existing.size, uri = uri.toString(),
            durationMs = info?.durationMs ?: 0, startOffsetMs = offset, valid = info != null && info.durationMs > 0,
        )
        val id = db.segments().insert(seg)
        val s = db.sessions().get(sessionId)
        if (s != null && info != null) {
            db.sessions().update(
                s.copy(
                    durationMs = offset + info.durationMs,
                    width = if (s.width == 0) info.width else s.width,
                    height = if (s.height == 0) info.height else s.height,
                    fps = if (s.fps == 0f) info.fps else s.fps,
                ),
            )
        }
        return seg.copy(id = id)
    }

    /** Imports an external video (kept in place, persistable permission taken by the caller). */
    suspend fun importVideo(uri: Uri, title: String, mode: SessionMode): Long? {
        val info = probe(uri) ?: return null
        val id = db.sessions().insert(
            SessionEntity(
                title = title, mode = mode, createdAt = System.currentTimeMillis(), status = SessionStatus.IMPORTED,
                imported = true, width = info.width, height = info.height, fps = info.fps,
            ),
        )
        addSegment(id, uri, info)
        return id
    }

    /**
     * Sessions left in RECORDING state belong to an interrupted recording. Every finalised
     * segment is checked; unreadable segments (e.g. the one being written during a crash) are
     * marked invalid but kept on disk, and the session becomes RECOVERED.
     */
    suspend fun recoverInterruptedSessions() {
        for (s in db.sessions().interrupted()) {
            val segs = db.segments().forSession(s.id).toMutableList()
            // segment files that exist on disk but were never registered
            val dir = sessionDir(s.id)
            val known = segs.map { it.uri }.toSet()
            dir.listFiles { f -> f.extension == "mp4" }?.sortedBy { it.name }?.forEach { f ->
                val u = Uri.fromFile(f).toString()
                if (u !in known) segs += addSegment(s.id, Uri.fromFile(f), probe(Uri.fromFile(f)))
            }
            var offset = 0L
            for (seg in db.segments().forSession(s.id)) {
                val info = probe(Uri.parse(seg.uri))
                val valid = info != null && info.durationMs > 0
                db.segments().update(seg.copy(valid = valid, durationMs = info?.durationMs ?: 0, startOffsetMs = offset))
                if (valid) offset += info!!.durationMs
            }
            db.sessions().update(s.copy(status = SessionStatus.RECOVERED, durationMs = offset))
        }
    }

    suspend fun deleteSession(s: SessionEntity) = withContext(Dispatchers.IO) {
        if (!s.imported) sessionDir(s.id).deleteRecursively()
        db.sessions().delete(s)
    }
}
