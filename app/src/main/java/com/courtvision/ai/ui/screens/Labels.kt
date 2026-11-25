package com.courtvision.ai.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.courtvision.ai.R
import com.courtvision.ai.data.AnalysisState
import com.courtvision.ai.data.SessionEntity
import com.courtvision.ai.data.SessionMode

@Composable
fun modeLabel(m: SessionMode): String = stringResource(
    when (m) {
        SessionMode.TENNIS_SINGLES -> R.string.mode_tennis_singles
        SessionMode.TENNIS_DOUBLES -> R.string.mode_tennis_doubles
        SessionMode.PICKLEBALL_SINGLES -> R.string.mode_pickle_singles
        SessionMode.PICKLEBALL_DOUBLES -> R.string.mode_pickle_doubles
        SessionMode.TRAINING_SOLO -> R.string.mode_training_solo
        SessionMode.TRAINING_PARTNER -> R.string.mode_training_partner
        SessionMode.TRAINING_SERVE -> R.string.mode_training_serve
        SessionMode.TRAINING_MACHINE -> R.string.mode_training_machine
        SessionMode.TRAINING_PRECISION -> R.string.mode_training_precision
        SessionMode.TECHNIQUE -> R.string.mode_technique
    },
)

@Composable
fun shotLabel(type: String): String = stringResource(
    when (type) {
        "SERVE" -> R.string.shot_serve
        "FOREHAND" -> R.string.shot_forehand
        "BACKHAND" -> R.string.shot_backhand
        "VOLLEY" -> R.string.shot_volley
        "SMASH" -> R.string.shot_smash
        "LOB" -> R.string.shot_lob
        "DROP_SHOT" -> R.string.shot_drop
        "RETURN" -> R.string.shot_return
        "DINK" -> R.string.shot_dink
        "DRIVE" -> R.string.shot_drive
        "THIRD_SHOT_DROP" -> R.string.shot_third
        else -> R.string.shot_unknown
    },
)

@Composable
fun reasonLabel(r: String): String = stringResource(
    when (r) {
        "ACE" -> R.string.reason_ace
        "DOUBLE_FAULT" -> R.string.reason_double_fault
        "WINNER" -> R.string.reason_winner
        "FORCED_ERROR" -> R.string.reason_forced
        "UNFORCED_ERROR" -> R.string.reason_unforced
        "OUT" -> R.string.reason_out
        "NET" -> R.string.reason_net
        "DOUBLE_BOUNCE" -> R.string.reason_double_bounce
        else -> R.string.reason_unknown
    },
)

@Composable
fun callLabel(c: String?): String = stringResource(
    when (c) { "IN" -> R.string.call_in; "OUT" -> R.string.call_out; else -> R.string.call_inconclusive },
)

@Composable
fun stageLabel(s: SessionEntity): String {
    val stage = stringResource(
        when (s.analysisStage) {
            "queued" -> R.string.stage_queued
            "court" -> R.string.stage_court
            "decode" -> R.string.stage_decode
            "cooling" -> R.string.stage_cooling
            "events" -> R.string.stage_events
            "calls" -> R.string.stage_calls
            "shots" -> R.string.stage_shots
            "score" -> R.string.stage_score
            "paused" -> R.string.stage_paused
            "done" -> R.string.stage_done
            "error" -> R.string.stage_error
            else -> R.string.stage_start
        },
    )
    return "$stage · ${(s.analysisProgress * 100).toInt()}%"
}

@Composable
fun errorLabel(code: String?): String = when {
    code == null -> ""
    code == "calibration_needed" -> stringResource(R.string.err_calibration)
    code == "no_valid_video" || code == "unreadable_video" || code == "no_video_track" -> stringResource(R.string.err_video)
    code == "too_hot" -> stringResource(R.string.err_hot)
    else -> stringResource(R.string.err_internal, code)
}

fun isAnalysisActive(s: AnalysisState) = s == AnalysisState.QUEUED || s == AnalysisState.RUNNING
