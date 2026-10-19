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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codingagent.agent.AgentTools
import com.codingagent.agent.ChatMessage
import com.codingagent.agent.ChatRole
import com.codingagent.model.ModelDownloadProgress
import com.codingagent.research.ResearchDisplayState
import com.codingagent.workspace.EditorDocument
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ResearchHit
import com.codingagent.workspace.TerminalEntry

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

@Composable
internal fun ChatSurface(
    messages: List<ChatMessage>,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    busy: Boolean,
    onStop: () -> Unit,
    pendingApproval: Boolean,
    approvalCount: Int,
    reason: String,
    onApprove: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "Type a request in plain English about your project.\nThe agent will look at your files and reply here.",
                        color = SoftGreen,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
            items(messages, key = { it.id }) { ChatBubble(it) }
        }
        if (pendingApproval) ApprovalCard(approvalCount, reason, onApprove)
        if (busy) {
            Text("Agent is working…", color = FluoroOrange, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Panel)
                .border(1.dp, NeonGreen.copy(alpha = 0.4f))
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Describe what you want fixed or added…", color = SoftGreen) },
                minLines = 2,
                maxLines = 6,
                textStyle = TextStyle(color = NeonGreen, fontSize = 16.sp, lineHeight = 22.sp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    TextButton(onClick = onStop) { Text("Stop", color = Danger) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = onSend,
                    enabled = input.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkPurple)
                ) { Text("Send", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
internal fun ApprovalCard(approvalCount: Int, reason: String, onApprove: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = RaisedPurple),
        border = androidx.compose.foundation.BorderStroke(1.dp, FluoroOrange)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CODE CHANGE REVIEW", color = FluoroOrange, fontWeight = FontWeight.Bold)
            Text(reason, color = NeonGreen, fontSize = 13.sp)
            Text("Two explicit approvals are required before a code transaction can proceed.", color = SoftGreen, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${approvalCount}/2", color = SoftGreen, modifier = Modifier.weight(1f))
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = FluoroOrange, contentColor = DarkPurple)
                ) { Text(if (approvalCount == 0) "Confirm" else "Confirm again") }
            }
        }
    }
}

@Composable
internal fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val isSystem = message.role == ChatRole.SYSTEM
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            when {
                isUser -> "YOU"
                isSystem -> "SYSTEM"
                else -> "AGENT"
            },
            color = if (isUser) SoftGreen else if (isSystem) FluoroOrange else NeonGreen,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) Panel else if (isSystem) RaisedPurple else Panel
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isUser) NeonGreen.copy(alpha = 0.5f) else if (isSystem) FluoroOrange.copy(alpha = 0.5f) else SoftGreen.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.92f else 0.96f)
        ) {
            Text(
                message.content,
                color = NeonGreen,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
internal fun FilesSurface(
    files: List<String>,
    query: String,
    onQuery: (String) -> Unit,
    path: String,
    onPath: (String) -> Unit,
    content: String,
    onContent: (String) -> Unit,
    document: EditorDocument?,
    tools: AgentTools?,
    mutationCoordinator: MutationCoordinator?,
    onStatus: (Pair<AgentStatus, String>) -> Unit
) {
    val filtered = remember(files, query) {
        if (query.isBlank()) files else files.filter { it.contains(query, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Filter files…", color = SoftGreen) },
            singleLine = true,
            colors = fieldColors()
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(0.4f).fillMaxSize()) {
                items(filtered, key = { it }) { file ->
                    Text(
                        file,
                        color = if (file == path) NeonGreen else SoftGreen,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPath(file)
                                tools?.read(file)?.let { onContent(it.content) }
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(0.6f).fillMaxSize()) {
                Text(path.ifBlank { "(no file selected)" }, color = SoftGreen, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = onContent,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    textStyle = TextStyle(color = NeonGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    colors = fieldColors()
                )
            }
        }
    }
}

@Composable
internal fun ReviewSurface(
    pending: Boolean,
    approvals: Int,
    reason: String,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Review", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (!pending) {
            Text("No pending code change proposal.", color = SoftGreen)
        } else {
            Text(reason, color = NeonGreen, fontSize = 14.sp)
            Text("Approvals: $approvals/2", color = SoftGreen)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onApprove, colors = ButtonDefaults.buttonColors(containerColor = FluoroOrange, contentColor = DarkPurple)) {
                    Text("Approve")
                }
                TextButton(onClick = onReject) { Text("Reject", color = Danger) }
            }
        }
    }
}

@Composable
internal fun TerminalSurface(
    command: String,
    onCommand: (String) -> Unit,
    history: List<TerminalEntry>,
    liveOutput: String,
    cwd: String,
    shell: String,
    timeoutSeconds: Long,
    running: Boolean,
    enabled: Boolean,
    onRun: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("cwd: $cwd · shell: $shell · timeout: ${timeoutSeconds}s", color = SoftGreen, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = command,
            onValueChange = onCommand,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Command…", color = SoftGreen) },
            singleLine = true,
            enabled = enabled && !running,
            colors = fieldColors()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (command.isNotBlank()) onRun(command) },
                enabled = enabled && !running && command.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkPurple)
            ) { Text("Run") }
            if (running) TextButton(onClick = onStop) { Text("Stop", color = Danger) }
            TextButton(onClick = onClear) { Text("Clear", color = SoftGreen) }
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Panel)
                .border(1.dp, SoftGreen.copy(alpha = 0.3f))
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            history.forEach { entry ->
                Text("$ ${entry.command}", color = FluoroOrange, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(
                    (entry.stdout + entry.stderr).ifBlank { "(no output, exit ${entry.exitCode})" },
                    color = NeonGreen,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(6.dp))
            }
            if (liveOutput.isNotBlank()) {
                Text(liveOutput, color = SoftGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
internal fun ResearchSurface(
    query: String,
    onQuery: (String) -> Unit,
    hits: List<ResearchHit>,
    error: String?,
    state: ResearchDisplayState,
    onSearch: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Research query…", color = SoftGreen) },
            colors = fieldColors()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (query.isNotBlank()) onSearch(query) },
            enabled = query.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkPurple)
        ) { Text("Search") }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = Danger, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits) { hit ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SoftGreen.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(hit.title, color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(hit.excerpt, color = SoftGreen, fontSize = 12.sp)
                        Text(hit.url, color = FluoroOrange, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

internal fun createEmptyProject(privateDir: File, nameHint: String? = null): File {
    val base = privateDir.resolve("projects")
    base.mkdirs()
    val name = (nameHint?.trim()?.takeIf { it.isNotEmpty() } ?: "project-${System.currentTimeMillis()}")
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val dir = base.resolve(name)
    if (!dir.exists()) dir.mkdirs()
    File(dir, "README.md").writeText("# $name\n\nCreated by Coding Agent.\n")
    return dir
}

internal fun importProject(context: Context, privateDir: File, uri: Uri): File {
    val takeFlags = ImportFlags
    try {
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
    } catch (_: SecurityException) {
    }
    val tree = DocumentFile.fromTreeUri(context, uri) ?: error("Could not open selected folder")
    val base = privateDir.resolve("projects")
    base.mkdirs()
    val name = (tree.name ?: "imported-${System.currentTimeMillis()}").replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val targetRoot = base.resolve(name)
    if (targetRoot.exists()) targetRoot.deleteRecursively()
    targetRoot.mkdirs()
    copyDocumentTree(context, tree, targetRoot, relativePath = "")
    return targetRoot
}

private fun copyDocumentTree(context: Context, source: DocumentFile, targetDir: File, relativePath: String) {
    for (child in source.listFiles()) {
        val name = child.name ?: continue
        if (child.isDirectory) {
            val next = File(targetDir, name)
            next.mkdirs()
            copyDocumentTree(context, child, next, if (relativePath.isEmpty()) name else "$relativePath/$name")
        } else if (child.isFile) {
            val target = File(targetDir, name)
            context.contentResolver.openInputStream(child.uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Could not read ${child.uri}")
            if (name == "gradlew" && relativePath.isEmpty()) target.setExecutable(true, false)
        }
    }
}

internal val ImportFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal fun fieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = NeonGreen,
    unfocusedTextColor = NeonGreen,
    focusedBorderColor = NeonGreen,
    unfocusedBorderColor = SoftGreen.copy(alpha = 0.5f),
    focusedLabelColor = SoftGreen,
    unfocusedLabelColor = SoftGreen,
    cursorColor = NeonGreen,
    focusedContainerColor = Panel,
    unfocusedContainerColor = Panel,
    focusedPlaceholderColor = SoftGreen,
    unfocusedPlaceholderColor = SoftGreen
)
