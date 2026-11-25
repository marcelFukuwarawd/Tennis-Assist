package com.courtvision.ai.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.courtvision.ai.CourtVisionApp
import com.courtvision.ai.R
import com.courtvision.ai.data.AppDatabase
import com.courtvision.ai.data.SessionEntity
import com.courtvision.ai.data.court
import com.courtvision.ai.data.nameA
import com.courtvision.ai.data.nameB
import com.courtvision.ai.data.scoringSession
import com.courtvision.ai.data.sport
import com.courtvision.ai.data.tennisRules
import com.courtvision.ai.data.toRecord
import com.courtvision.ai.data.toScoringEvents
import com.courtvision.ai.util.Storage
import com.courtvision.ai.util.formatDuration
import com.courtvision.ai.util.formatNum
import com.courtvision.ai.util.formatPct
import com.courtvision.analysis.Ratio
import com.courtvision.analysis.StatisticsCalculator
import com.courtvision.scoring.Side
import com.courtvision.vision.Vec2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** CSV / JSON / PDF exports and full local ZIP backup. Files stay on the device unless the user shares them. */
class DataExporter(private val context: Context) {
    private val app = context.applicationContext as CourtVisionApp
    private val db = app.db

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40)
    private fun csv(v: Any?): String = when (v) {
        null -> ""
        is String -> "\"" + v.replace("\"", "\"\"") + "\""
        else -> v.toString()
    }

    suspend fun csv(s: SessionEntity): File = withContext(Dispatchers.IO) {
        val dir = File(Storage.exportsDir(context), "session_${s.id}_csv").apply { mkdirs() }
        File(dir, "points.csv").printWriter().use { w ->
            w.println("order,winner,reason,serve,start_ms,end_ms,auto,confidence,needs_review")
            db.points().forSession(s.id).forEach { p -> w.println(listOf(p.order + 1, if (p.winnerA) s.nameA() else s.nameB(), p.reason, p.serveNumber, p.startMs, p.endMs, p.auto, p.confidence, p.needsReview).joinToString(",") { csv(it) }) }
        }
        File(dir, "shots.csv").printWriter().use { w ->
            w.println("time_ms,side,player_id,type,grip,speed_kmh,hitter_x,hitter_y,bounce_x,bounce_y,call,p_in,rally,index_in_rally,edited")
            db.analysis().shots(s.id).forEach { sh -> w.println(listOf(sh.timeMs, sh.sideA?.let { if (it) "A" else "B" }, sh.playerId, sh.type, sh.grip, sh.speedKmh, sh.hitterX, sh.hitterY, sh.bounceX, sh.bounceY, sh.call, sh.probabilityIn, sh.rallyIndex, sh.indexInRally, sh.edited).joinToString(",") { csv(it) }) }
        }
        File(dir, "bounces.csv").printWriter().use { w ->
            w.println("time_ms,x_m,y_m,sigma_x_m,sigma_y_m,call,p_in,calibrated_probability")
            db.analysis().bounces(s.id).forEach { b -> w.println(listOf(b.timeMs, b.x, b.y, b.sigmaX, b.sigmaY, b.call, b.probabilityIn, b.calibrated).joinToString(",") { csv(it) }) }
        }
        File(dir, "rallies.csv").printWriter().use { w ->
            w.println("index,start_ms,end_ms,shots")
            db.analysis().rallies(s.id).forEach { r -> w.println(listOf(r.index + 1, r.startMs, r.endMs, r.shots).joinToString(",")) }
        }
        val zip = File(Storage.exportsDir(context), "${safe(s.title)}_csv.zip")
        zipFiles(zip, dir.listFiles()!!.toList(), dir)
        zip
    }

    suspend fun json(s: SessionEntity): File = withContext(Dispatchers.IO) {
        val points = db.points().forSession(s.id)
        val obj = buildJsonObject {
            put("format", "courtvision-session-v1")
            put("title", s.title); put("mode", s.mode.name); put("created_at", s.createdAt); put("duration_ms", s.durationMs)
            put("player_a", s.nameA()); put("player_b", s.nameB()); put("rules", s.rulesJson); put("calibration", s.calibrationJson?.let { JsonPrimitive(it) } ?: JsonNull)
            put("points", JsonArray(points.map { p -> buildJsonObject { put("winner", if (p.winnerA) "A" else "B"); put("reason", p.reason); put("serve", p.serveNumber); put("start_ms", p.startMs); put("end_ms", p.endMs); put("auto", p.auto); put("confidence", p.confidence); put("needs_review", p.needsReview) } }))
            put("shots", JsonArray(db.analysis().shots(s.id).map { sh -> buildJsonObject { put("t", sh.timeMs); put("side", sh.sideA?.let { if (it) "A" else "B" }); put("type", sh.type); put("speed_kmh", sh.speedKmh); put("bounce_x", sh.bounceX); put("bounce_y", sh.bounceY); put("call", sh.call) } }))
            put("bounces", JsonArray(db.analysis().bounces(s.id).map { b -> buildJsonObject { put("t", b.timeMs); put("x", b.x); put("y", b.y); put("sigma_x", b.sigmaX); put("sigma_y", b.sigmaY); put("call", b.call); put("p_in", b.probabilityIn) } }))
            put("final_score", s.scoringSession().board(points.toScoringEvents()).callout)
        }
        File(Storage.exportsDir(context), "${safe(s.title)}.json").apply { writeText(obj.toString()) }
    }

    /** One-page A4 PDF report with the score, key statistics and a bounce map. */
    suspend fun pdf(s: SessionEntity): File = withContext(Dispatchers.IO) {
        val points = db.points().forSession(s.id)
        val shots = db.analysis().shots(s.id).map { it.toRecord() }
        val rallies = db.analysis().rallies(s.id).map { it.toRecord() }
        val st = StatisticsCalculator.compute(s.sport(), s.court(), s.tennisRules(), if (s.firstServerA) Side.A else Side.B, points.map { it.toRecord() }, shots, rallies)
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val title = Paint().apply { textSize = 20f; isFakeBoldText = true; color = Color.BLACK }
        val text = Paint().apply { textSize = 11f; color = Color.DKGRAY }
        var y = 50f
        c.drawText(context.getString(R.string.app_name) + " – " + s.title, 40f, y, title); y += 22
        c.drawText("${s.nameA()} vs ${s.nameB()} · ${formatDuration(s.durationMs)}", 40f, y, text); y += 16
        if (s.mode.isMatch) { c.drawText(context.getString(R.string.tab_score) + ": " + s.scoringSession().board(points.toScoringEvents()).callout, 40f, y, text); y += 16 }
        fun r(x: Ratio) = if (x.total == 0) "—" else "${formatPct(x.value)} (${x.hits}/${x.total})"
        val rows = listOf(
            context.getString(R.string.points_won) to ("${st.a.pointsWon}" to "${st.b.pointsWon}"),
            context.getString(R.string.reason_ace) to ("${st.a.aces}" to "${st.b.aces}"),
            context.getString(R.string.reason_double_fault) to ("${st.a.doubleFaults}" to "${st.b.doubleFaults}"),
            context.getString(R.string.first_serve_in) to (r(st.a.firstServeIn) to r(st.b.firstServeIn)),
            context.getString(R.string.break_points) to (r(st.a.breakPoints) to r(st.b.breakPoints)),
            context.getString(R.string.total_shots) to ("${st.a.shots}" to "${st.b.shots}"),
            context.getString(R.string.accuracy) to (r(st.a.accuracy) to r(st.b.accuracy)),
            context.getString(R.string.max_speed) to (formatNum(st.a.maxSpeedKmh) to formatNum(st.b.maxSpeedKmh)),
            context.getString(R.string.longest_rally) to ((st.longestRallyShots?.toString() ?: "—") to ""),
        )
        y += 10
        rows.forEach { (k, v) -> c.drawText(k, 40f, y, text); c.drawText(v.first, 300f, y, text); c.drawText(v.second, 420f, y, text); y += 16 }
        // bounce map
        val court = s.court()
        val top = y + 20; val h = 842 - top - 60; val scale = (h / court.length).toFloat()
        val left = (595 - court.doublesWidth * scale).toFloat() / 2
        val line = Paint().apply { color = Color.rgb(31, 78, 121); strokeWidth = 1.2f; style = Paint.Style.STROKE }
        fun map(p: Vec2) = (left + p.x * scale).toFloat() to (top + p.y * scale).toFloat()
        court.lines().forEach { l -> val (ax, ay) = map(l.a); val (bx, by) = map(l.b); c.drawLine(ax, ay, bx, by, line) }
        val dot = Paint().apply { style = Paint.Style.FILL }
        db.analysis().bounces(s.id).forEach { b ->
            dot.color = when (b.call) { "IN" -> Color.rgb(30, 160, 90); "OUT" -> Color.rgb(220, 60, 60); else -> Color.rgb(230, 170, 30) }
            val (x, yy) = map(Vec2(b.x, b.y)); c.drawCircle(x, yy, 2.5f, dot)
        }
        c.drawText(context.getString(R.string.pdf_disclaimer), 40f, 830f, text)
        doc.finishPage(page)
        val f = File(Storage.exportsDir(context), "${safe(s.title)}.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        f
    }

    /** Full backup: database (+ optionally the recorded videos) into a ZIP written to [target]. */
    suspend fun backup(target: Uri, includeVideos: Boolean) = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        context.contentResolver.openOutputStream(target)!!.use { os ->
            ZipOutputStream(os).use { zip ->
                zip.putNextEntry(ZipEntry("db/${AppDatabase.NAME}")); dbFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                File(context.filesDir, "datastore").listFiles()?.forEach { f -> zip.putNextEntry(ZipEntry("datastore/${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                if (includeVideos) {
                    val root = Storage.moviesRoot(context)
                    root.walkTopDown().filter { it.isFile }.forEach { f ->
                        zip.putNextEntry(ZipEntry("movies/" + f.relativeTo(root).path)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
            }
        }
    }

    /** Restores a backup ZIP. The app must be restarted afterwards (the database is replaced). */
    suspend fun restore(source: Uri): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var hasDb = false
        context.contentResolver.openInputStream(source)!!.use { ins ->
            ZipInputStream(ins).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(tmp, e.name)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath)) continue // zip-slip protection
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    if (e.name == "db/${AppDatabase.NAME}") hasDb = true
                }
            }
        }
        if (!hasDb) return@withContext false
        db.close()
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
        File(tmp, "db/${AppDatabase.NAME}").copyTo(dbFile, overwrite = true)
        File(tmp, "datastore").listFiles()?.forEach { it.copyTo(File(File(context.filesDir, "datastore"), it.name), overwrite = true) }
        File(tmp, "movies").takeIf { it.exists() }?.copyRecursively(Storage.moviesRoot(context), overwrite = true)
        tmp.deleteRecursively()
        true
    }

    fun share(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun zipFiles(zip: File, files: List<File>, base: File) {
        ZipOutputStream(zip.outputStream()).use { z ->
            files.forEach { f -> z.putNextEntry(ZipEntry(f.relativeTo(base).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
    }
}
