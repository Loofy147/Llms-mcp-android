package com.hicham.llmchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.hicham.llmchat.ui.ChatScreen
import com.hicham.llmchat.ui.ChatViewModel
import com.hicham.llmchat.ui.LlmChatTheme
import com.hicham.llmchat.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assistantRuntime = AssistantRuntime(applicationContext)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ChatViewModel::class.java)
                return ChatViewModel(application, assistantRuntime) as T
            }
        }
        val viewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]

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
