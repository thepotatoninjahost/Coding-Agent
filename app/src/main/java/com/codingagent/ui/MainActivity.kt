package com.codingagent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.codingagent.core.AgentAction
import com.codingagent.core.AgentActionCategory
import com.codingagent.core.AgentConstitution
import com.codingagent.core.AgentKnowledge
import com.codingagent.core.AgentJournal
import com.codingagent.core.AgentTools
import com.codingagent.core.ChatMessage
import com.codingagent.core.ChatRole
import com.codingagent.core.ChatWorkspace
import com.codingagent.core.CodingAgentRuntime
import com.codingagent.core.DuckDuckGoResearchProvider
import com.codingagent.core.DurableDeepResearchProvider
import com.codingagent.core.DeepResearchProgress
import com.codingagent.core.ResearchDisplayState
import com.codingagent.core.ResearchModeDetector
import com.codingagent.core.EditorDocument
import com.codingagent.core.KnowledgeBase
import com.codingagent.core.LocalStore
import com.codingagent.core.ModelDownloadProgress
import com.codingagent.core.NexaModelProvisioner
import com.codingagent.core.NexaLocalModelGateway
import com.codingagent.core.ProjectWorkspace
import com.codingagent.core.ResearchHit
import com.codingagent.core.TerminalEntry
import com.codingagent.core.MutationApprovalResult
import com.codingagent.core.MutationCoordinator
import com.codingagent.core.AutonomousAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.codingagent.core.PendingChangeProposal

private val Ink = Color(0xFFE8EDF2)
private val Muted = Color(0xFF94A0AE)
private val Canvas = Color(0xFF0A0D11)
private val Panel = Color(0xFF131922)
private val PanelRaised = Color(0xFF1A2330)
private val Line = Color(0xFF2B3848)
private val Accent = Color(0xFF8FE36B)
private val Blue = Color(0xFF8CB9FF)
private val Amber = Color(0xFFFFC857)
private val Danger = Color(0xFFFF756D)

private enum class SurfaceTab(val label: String) { CHAT("Chat"), FILES("Files"), REVIEW("Review"), TERMINAL("Terminal"), RESEARCH("Research") }
private enum class AgentStatus(val label: String, val color: Color) { READY("Ready", Accent), RESEARCHING("Researching", Blue), PLANNING("Planning", Blue), EDITING("Editing", Amber), APPROVAL("Waiting for approval", Amber), RUNNING("Verifying", Blue), STOPPED("Stopped", Danger) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CodingAgentApp(filesDir) }
    }
}
