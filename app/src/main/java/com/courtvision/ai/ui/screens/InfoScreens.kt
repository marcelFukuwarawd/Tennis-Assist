package com.courtvision.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.ui.SectionCard

@Composable
fun PrivacyScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.privacy_policy), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.privacy_text))
    }
}

private data class Lic(val name: String, val license: String, val note: String)

private val licenses = listOf(
    Lic("MediaPipe Tasks Vision 0.10.20 (Google)", "Apache License 2.0", "On-device inference runtime"),
    Lic("EfficientDet-Lite0 int8 (MediaPipe model)", "Apache License 2.0", "SHA-256 0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb"),
    Lic("Pose Landmarker Lite float16 (MediaPipe model)", "Apache License 2.0", "SHA-256 59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"),
    Lic("AndroidX (Compose, CameraX, Media3, Room, WorkManager, DataStore, Navigation, AppCompat)", "Apache License 2.0", "Google / AOSP"),
    Lic("Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0", "JetBrains"),
)

@Composable
fun LicensesScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.licenses), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.licenses_info), style = MaterialTheme.typography.bodySmall)
        licenses.forEach { l -> SectionCard(l.name) { Text(l.license, fontWeight = FontWeight.SemiBold); Text(l.note, style = MaterialTheme.typography.labelSmall) } }
    }
}
