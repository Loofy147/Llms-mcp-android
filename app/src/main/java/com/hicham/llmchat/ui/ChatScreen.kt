package com.hicham.llmchat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hicham.llmchat.model.ChatMessage
import com.hicham.llmchat.model.ContentBlock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, onOpenSettings: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LLM Chat") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages) { message -> MessageBubble(message) }
                if (uiState.isStreaming) {
                    item { Text("…", modifier = Modifier.padding(8.dp)) }
                }
            }

            uiState.error?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = viewModel::updateInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    enabled = !uiState.isStreaming
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { viewModel.send() }, enabled = !uiState.isStreaming) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.role == "user") Alignment.End else Alignment.Start
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(
            color = if (message.role == "user")
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                for (block in message.blocks) BlockContent(block)
            }
        }
    }
}

@Composable
private fun BlockContent(block: ContentBlock) {
    when (block) {
        is ContentBlock.Text -> if (block.text.isNotBlank()) Text(block.text)
        is ContentBlock.ToolUse -> Text("🔧 called ${block.name}(${block.inputJson})", style = MaterialTheme.typography.bodySmall)
        is ContentBlock.ToolResult -> Text("↳ ${block.content}", style = MaterialTheme.typography.bodySmall)
        is ContentBlock.McpToolUse -> Text("🔌 ${block.serverName} → ${block.name}(${block.inputJson})", style = MaterialTheme.typography.bodySmall)
        is ContentBlock.McpToolResult -> Text("↳ ${block.content}", style = MaterialTheme.typography.bodySmall)
    }
}
