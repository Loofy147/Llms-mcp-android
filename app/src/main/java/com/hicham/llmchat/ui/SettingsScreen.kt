package com.hicham.llmchat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.hicham.llmchat.model.McpServerConfig
import com.hicham.llmchat.runtime.AndroidWorkspaceAuthority
import com.hicham.llmchat.runtime.WorkspaceGrant
import com.hicham.llmchat.runtime.WorkspaceOperation
import java.util.UUID

private val MODELS = listOf("claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001", "claude-fable-5")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val workspaceAuthority = remember(context) { AndroidWorkspaceAuthority(context) }
    var workspaces by remember { mutableStateOf(workspaceAuthority.listGrants()) }

    val workspacePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val grant = WorkspaceGrant(
                id = UUID.randomUUID().toString(),
                displayName = "Workspace",
                authority = "android.saf.tree",
                treeUri = uri.toString(),
                operations = setOf(WorkspaceOperation.READ, WorkspaceOperation.LIST, WorkspaceOperation.HASH),
                grantedAtEpochMs = System.currentTimeMillis()
            )
            runCatching { workspaceAuthority.registerGrant(grant) }
                .onSuccess { workspaces = workspaceAuthority.listGrants() }
        }
    }

    val settings by viewModel.settings.collectAsState()
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var systemPrompt by remember(settings) { mutableStateOf(settings.systemPrompt) }
    var nativeTools by remember(settings) { mutableStateOf(settings.nativeToolsEnabled) }
    var servers by remember(settings) { mutableStateOf(settings.mcpServers) }

    var newServerName by remember { mutableStateOf("") }
    var newServerUrl by remember { mutableStateOf("") }
    var newServerToken by remember { mutableStateOf("") }

    fun persist() {
        viewModel.updateSettings(
            settings.copy(
                apiKey = apiKey,
                model = model,
                systemPrompt = systemPrompt,
                nativeToolsEnabled = nativeTools,
                mcpServers = servers
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { persist(); onDone() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item {
                Text("Anthropic API key", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Stored locally on-device in protected credential storage. Sent only to api.anthropic.com.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                Text("Model", style = MaterialTheme.typography.labelLarge)
                Column {
                    MODELS.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = model == m, onClick = { model = m })
                            Text(m)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                Text("System prompt (optional)", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = nativeTools, onCheckedChange = { nativeTools = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable built-in tools (time, calculator)")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                Text("Developer workspaces", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Grant a specific Android folder for future read-only developer capabilities. The selected folder remains the authority boundary.",
                    style = MaterialTheme.typography.bodySmall
                )
                workspaces.forEach { workspace ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(workspace.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(workspace.treeUri, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        IconButton(onClick = {
                            workspaceAuthority.revokeGrant(workspace.id)
                            workspaces = workspaceAuthority.listGrants()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Revoke workspace")
                        }
                    }
                }
                Button(onClick = { workspacePicker.launch(null) }) {
                    Text("Add workspace")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                Text("MCP servers", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Configured MCP servers are forwarded by the provider adapter. Local authorization remains owned by this application.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(servers) { server ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.bodyMedium)
                        Text(server.url, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        viewModel.removeMcpServer(server)
                        servers = servers.filter { it != server }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = newServerName, onValueChange = { newServerName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = newServerUrl, onValueChange = { newServerUrl = it }, label = { Text("https:// URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = newServerToken, onValueChange = { newServerToken = it }, label = { Text("Auth token (optional)") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        if (newServerName.isNotBlank() && newServerUrl.startsWith("https://")) {
                            servers = servers + McpServerConfig(newServerName, newServerUrl, newServerToken.ifBlank { null })
                            newServerName = ""; newServerUrl = ""; newServerToken = ""
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Add server") }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { persist(); onDone() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save")
                }
            }
        }
    }
}
