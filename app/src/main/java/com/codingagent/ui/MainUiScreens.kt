package com.codingagent.ui

/**
 * ONE JOB: Chat / Files / Review / Terminal / Research surfaces.
 */

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codingagent.core.AgentTools
import com.codingagent.core.ChatMessage
import com.codingagent.core.ChatRole
import com.codingagent.core.DeepResearchProgress
import com.codingagent.core.EditorDocument
import com.codingagent.core.ModelDownloadProgress
import com.codingagent.core.MutationCoordinator
import com.codingagent.core.ResearchDisplayState
import com.codingagent.core.ResearchHit
import com.codingagent.core.TerminalEntry

@Composable
internal fun CompactStatusBar(
    status: AgentStatus,
    detail: String,
    mounted: Boolean,
    modelStatus: String,
    modelProgress: ModelDownloadProgress?,
    onImport: () -> Unit,
    onNewProject: () -> Unit,
    onModelImport: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .border(1.dp, NeonGreen.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CODING AGENT", color = NeonGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (mounted) "project mounted · $modelStatus" else "no project · $modelStatus",
                    color = SoftGreen,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
            if (status != AgentStatus.READY && status != AgentStatus.STOPPED) {
                TextButton(onClick = onStop) { Text("Stop", color = Danger, fontSize = 12.sp) }
            }
            TextButton(onClick = onModelImport) { Text("Model", color = FluoroOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            TextButton(onClick = onNewProject) { Text("New", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            TextButton(onClick = onImport) { Text(if (mounted) "Switch" else "Import", color = SoftGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(status.color, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(status.label, color = status.color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(detail, color = SoftGreen, fontSize = 11.sp, maxLines = 1)
        }
        if (status == AgentStatus.RESEARCHING || status == AgentStatus.PLANNING || status == AgentStatus.WORKING || status == AgentStatus.MODEL || status == AgentStatus.TOOL || status == AgentStatus.RUNNING || modelProgress?.phase == "downloading") {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { ((modelProgress?.percent ?: 0) / 100f).coerceIn(0f, 1f) },
                Modifier.fillMaxWidth(),
                color = status.color,
                trackColor = Line
            )
        }
    }
}
