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
import com.codingagent.workspace.ProjectPaths
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
                    if (mounted) "Project open · $modelStatus" else "No project yet · $modelStatus",
                    color = SoftGreen,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
            if (status != AgentStatus.READY && status != AgentStatus.STOPPED) {
                TextButton(onClick = onStop) { Text("Stop", color = Danger, fontSize = 12.sp) }
            }
            TextButton(onClick = onModelImport) { Text("Setup", color = FluoroOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            TextButton(onClick = onNewProject) { Text("New", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            TextButton(onClick = onImport) { Text(if (mounted) "Switch" else "Import", color = SoftGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(status.color, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(status.label, color = status.color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(detail, color = SoftGreen, fontSize = 11.sp, maxLines = 4)
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
                        "Tell me what you want in ordinary words.\nI will look at your project and answer here. I ask before I save any change.",
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
            Text("Working on what you asked.", color = FluoroOrange, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
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
                placeholder = { Text("Say what you want, in ordinary words…", color = SoftGreen) },
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
            Text("PLEASE CHECK THIS", color = FluoroOrange, fontWeight = FontWeight.Bold)
            Text(reason, color = NeonGreen, fontSize = 13.sp)
            Text("Say yes twice, on purpose, before anything is saved.", color = SoftGreen, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${approvalCount}/2", color = SoftGreen, modifier = Modifier.weight(1f))
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = FluoroOrange, contentColor = DarkPurple)
                ) { Text(if (approvalCount == 0) "Say yes" else "Say yes again") }
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
                isSystem -> "NOTE"
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
    onOpen: (String) -> Unit,
    content: String,
    onContent: (String) -> Unit,
    document: EditorDocument?,
    tools: AgentTools?,
    mutationCoordinator: MutationCoordinator?,
    onSave: () -> Unit
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
                            .clickable { onOpen(file) }
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(0.6f).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        path.ifBlank { "Tap a file to open it" },
                        color = SoftGreen,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onSave,
                        enabled = path.isNotBlank() && tools != null && mutationCoordinator != null,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkPurple)
                    ) { Text("Save") }
                }
                if (document?.dirty == true) {
                    Text("Unsaved edits", color = FluoroOrange, fontSize = 11.sp)
                }
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
            Text("Nothing is waiting for you to approve.", color = SoftGreen)
        } else {
            Text(reason, color = NeonGreen, fontSize = 14.sp)
            Text("Yes so far: $approvals of 2", color = SoftGreen)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onApprove, colors = ButtonDefaults.buttonColors(containerColor = FluoroOrange, contentColor = DarkPurple)) {
                    Text("Say yes")
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
        Text(
            "This runs a simple command in your project. It is not a full computer. Folder: $cwd. Tool: $shell. It stops after $timeoutSeconds seconds.",
            color = SoftGreen,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = command,
            onValueChange = onCommand,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Type a simple command", color = SoftGreen) },
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
            placeholder = { Text("What do you want to look up?", color = SoftGreen) },
            colors = fieldColors()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (query.isNotBlank()) onSearch(query) },
            enabled = query.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkPurple)
        ) { Text("Search") }
        Spacer(Modifier.height(8.dp))
        Text(
            when (state.phase) {
                "searching" -> "Looking on the internet…"
                "results" -> "${state.fullSources} pages found"
                "empty" -> "Nothing came up. Try different words."
                "failed" -> "The lookup failed."
                else -> "This looks up public pages. It does not change your project, and it does not need a password."
            },
            color = SoftGreen,
            fontSize = 12.sp
        )
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

/**
 * Create a genuinely empty project under app-private storage.
 */
internal fun createEmptyProject(privateDir: File, nameHint: String? = null): File {
    val projectsRoot = privateDir.resolve("projects")
    if (!projectsRoot.exists()) require(projectsRoot.mkdirs()) { "Could not create projects root" }
    val safeName = nameHint
        ?.trim()
        ?.replace(Regex("[^A-Za-z0-9._-]"), "-")
        ?.trim('-')
        ?.take(48)
        ?.ifBlank { null }
    val dirName = if (safeName != null) {
        "project-$safeName-${System.currentTimeMillis()}"
    } else {
        "project-${System.currentTimeMillis()}"
    }
    val destination = projectsRoot.resolve(dirName)
    require(destination.mkdirs()) { "Could not create empty project storage" }
    destination.resolve("README.md").writeText(
        "# ${safeName ?: "New project"}\n\nEmpty workspace created by Coding Agent.\nAsk the agent to add files, or import sources.\n"
    )
    return destination
}

internal const val MAX_PROJECT_IMPORT_FILES = 10_000
internal const val MAX_PROJECT_IMPORT_BYTES = 250L * 1024L * 1024L
internal const val MAX_PROJECT_IMPORT_DEPTH = 64

internal data class ProjectImportBudget(
    var files: Int = 0,
    var bytes: Long = 0L
)

internal fun isReservedProjectImportEntry(relativePath: String, name: String): Boolean {
    val candidate = if (relativePath.isEmpty()) name else relativePath + "/" + name
    return ProjectPaths.isSensitivePath(candidate)
}

internal fun validateProjectImportEntryName(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." &&
        !name.contains('/') && !name.contains('\\') && name.none { it.isISOControl() }) {
        "Project entry has an unsafe name"
    }
}

internal fun importProject(context: Context, privateDir: File, uri: Uri): File {
    val source = DocumentFile.fromTreeUri(context, uri) ?: error("Folder is unavailable")
    val projects = privateDir.resolve("projects")
    require(projects.isDirectory || projects.mkdirs()) { "Could not create project storage" }
    val destination = projects.resolve("project-${System.currentTimeMillis()}")
    require(destination.mkdirs()) { "Could not create project storage" }
    try {
        copyDocumentTree(context, source, destination)
        return destination
    } catch (error: Exception) {
        destination.deleteRecursively()
        throw error
    }
}

internal fun copyDocumentTree(
    context: Context,
    source: DocumentFile,
    destination: File,
    depth: Int = 0,
    relativePath: String = "",
    budget: ProjectImportBudget = ProjectImportBudget()
) {
    require(depth <= MAX_PROJECT_IMPORT_DEPTH) { "Project import exceeded the maximum folder depth" }
    val canonicalRoot = destination.canonicalFile.toPath()
    for (child in source.listFiles()) {
        val name = child.name ?: error("Project entry has no name")
        validateProjectImportEntryName(name)
        val childRelativePath = if (relativePath.isEmpty()) name else "$relativePath/$name"
        if (isReservedProjectImportEntry(relativePath, name)) continue
        val target = destination.resolve(name).canonicalFile
        require(target.toPath().startsWith(canonicalRoot)) { "Project entry escapes the import destination" }
        budget.files += 1
        require(budget.files <= MAX_PROJECT_IMPORT_FILES) { "Project import exceeds $MAX_PROJECT_IMPORT_FILES entries" }
        if (child.isDirectory) {
            require(target.isDirectory || target.mkdirs()) { "Could not create ${target.path}" }
            copyDocumentTree(context, child, target, depth + 1, childRelativePath, budget)
        } else if (child.isFile) {
            require(target.parentFile?.canonicalFile?.toPath()?.startsWith(canonicalRoot) == true) {
                "Project file escapes the import destination"
            }
            context.contentResolver.openInputStream(child.uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        budget.bytes += read
                        require(budget.bytes <= MAX_PROJECT_IMPORT_BYTES) {
                            "Project import exceeds the $MAX_PROJECT_IMPORT_BYTES byte limit"
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Could not read ${child.uri}")
            if (name == "gradlew" && relativePath.isEmpty()) target.setExecutable(true, false)
        }
    }
}

internal val ImportFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

@Composable
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
