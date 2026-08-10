package com.codingagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CodingAgentApp(filesDir) }
    }
}

// CodingAgentApp lives in artifacts/CodingAgentApp.kt — upload that file to
// app/src/main/java/com/codingagent/ui/CodingAgentApp.kt on GitHub to complete the UI.
// MainUiScreens.kt and UiTheme.kt are already on the repo.
