package com.hicham.llmchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hicham.llmchat.ui.ChatScreen
import com.hicham.llmchat.ui.ChatViewModel
import com.hicham.llmchat.ui.LlmChatTheme
import com.hicham.llmchat.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LlmChatTheme {
                Surface(modifier = Modifier) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        SettingsScreen(viewModel = viewModel, onDone = { showSettings = false })
                    } else {
                        ChatScreen(viewModel = viewModel, onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }
}
