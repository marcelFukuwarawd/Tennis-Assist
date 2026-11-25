package com.courtvision.ai

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.courtvision.ai.data.AppSettings
import com.courtvision.ai.ui.AppNavigation
import com.courtvision.ai.ui.CourtVisionTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CourtVisionApp
        setContent {
            val settings by app.settings.settings.collectAsState(initial = AppSettings())
            CourtVisionTheme(settings.theme) { AppNavigation() }
        }
    }
}
