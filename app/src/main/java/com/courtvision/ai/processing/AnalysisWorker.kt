package com.courtvision.ai.processing

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.courtvision.ai.CourtVisionApp
import com.courtvision.ai.R
import com.courtvision.ai.data.AnalysisState
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [AnalysisPipeline] as a foreground job. Progress is persisted, so pausing, cancelling,
 * process death or a reboot never lose more than a few seconds of work: the next run resumes
 * from the checkpoint.
 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CourtVisionApp
        val id = inputData.getLong(KEY_SESSION, -1)
        if (id < 0) return Result.failure()
        val dao = app.db.sessions()
        runCatching { setForeground(foreground(id, 0f, "")) }
        dao.setAnalysis(id, AnalysisState.RUNNING, dao.get(id)?.analysisProgress ?: 0f, "start", null)
        return try {
            AnalysisPipeline(
                context = applicationContext, db = app.db, settings = app.settings.current(),
                progress = { stage, value ->
                    val v = if (value < 0) dao.get(id)?.analysisProgress ?: 0f else value
                    dao.setAnalysis(id, AnalysisState.RUNNING, v, stage, null)
                    setProgress(workDataOf("p" to v, "stage" to stage))
                    runCatching { setForeground(foreground(id, v, stage)) }
                },
                isStopped = { isStopped },
            ).run(id)
            if (isStopped) Result.success() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AnalysisException) {
            dao.setAnalysis(id, if (e.code == "too_hot") AnalysisState.PAUSED else AnalysisState.FAILED, dao.get(id)?.analysisProgress ?: 0f, "error", e.code)
            Result.failure()
        } catch (e: Throwable) {
            dao.setAnalysis(id, AnalysisState.FAILED, dao.get(id)?.analysisProgress ?: 0f, "error", "internal:" + (e.message ?: e.javaClass.simpleName))
            Result.failure()
        }
    }

    private fun foreground(id: Long, p: Float, stage: String): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CourtVisionApp.CHANNEL_ANALYSIS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.analysis_running))
            .setContentText(stage)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (p * 100).toInt(), false)
            .build()
        return ForegroundInfo(id.toInt() + 1000, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_SESSION = "session"
        const val TAG = "analysis"
        fun name(id: Long) = "analysis_$id"
    }
}

/** Queue management: enqueue, pause, resume, cancel and partial reprocessing. */
object AnalysisScheduler {
    suspend fun enqueue(context: Context, sessionId: Long) {
        val app = context.applicationContext as CourtVisionApp
        app.db.sessions().setAnalysis(sessionId, AnalysisState.QUEUED, app.db.sessions().get(sessionId)?.analysisProgress ?: 0f, "queued", null)
        val req = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(workDataOf(AnalysisWorker.KEY_SESSION to sessionId))
            .addTag(AnalysisWorker.TAG)
            .build()
        // APPEND keeps a sequential queue across sessions
        WorkManager.getInstance(context).enqueueUniqueWork(AnalysisWorker.name(sessionId), ExistingWorkPolicy.KEEP, req)
    }

    suspend fun pause(context: Context, sessionId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(AnalysisWorker.name(sessionId))
        val app = context.applicationContext as CourtVisionApp
        val s = app.db.sessions().get(sessionId) ?: return
        app.db.sessions().setAnalysis(sessionId, AnalysisState.PAUSED, s.analysisProgress, "paused", null)
    }

    suspend fun cancel(context: Context, sessionId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(AnalysisWorker.name(sessionId))
        val app = context.applicationContext as CourtVisionApp
        app.db.sessions().setAnalysis(sessionId, AnalysisState.NONE, 0f, "", null)
        app.db.sessions().setCheckpoint(sessionId, 0)
    }

    /** Re-analyses from [fromMs] onwards (earlier results are kept). */
    suspend fun reprocessFrom(context: Context, sessionId: Long, fromMs: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(AnalysisWorker.name(sessionId))
        val app = context.applicationContext as CourtVisionApp
        app.db.sessions().setCheckpoint(sessionId, fromMs.coerceAtLeast(0))
        enqueue(context, sessionId)
    }
}
