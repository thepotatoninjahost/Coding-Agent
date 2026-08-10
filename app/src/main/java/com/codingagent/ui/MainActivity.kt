package com.codingagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Temporary shell so the GitHub Actions build can compile after a bad
 * placeholder upload of this file. This is NOT the full app UI.
 *
 * Use Coding-Agent-debug.apk or rebuild from Coding-Agent-build-verified.zip
 * for the real interface. Replace this file with the full MainActivity next.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MinimalShell() }
    }
}

@Composable
private fun MinimalShell() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Coding Agent",
            color = Color(0xFF39FF14),
            fontSize = 20.sp
        )
        Text(
            "Full UI temporarily replaced so the GitHub build can succeed. Install Coding-Agent-debug.apk or rebuild from Coding-Agent-build-verified.zip for the complete app.",
            color = Color(0xFF7ACC7A),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}
