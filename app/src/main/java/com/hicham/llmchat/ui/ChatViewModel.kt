package com.hicham.llmchat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hicham.llmchat.data.AnthropicClient
import com.hicham.llmchat.data.ConversationListener
import com.hicham.llmchat.data.SettingsStore
import com.hicham.llmchat.model.AppSettings
import com.hicham.llmchat.model.ChatMessage
import com.hicham.llmchat.model.ContentBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val error: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        settingsStore.save(newSettings)
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun send() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || _uiState.value.isStreaming) return
        if (_settings.value.apiKey.isBlank()) {
            _uiState.update { it.copy(error = "Add your Anthropic API key in Settings first.") }
            return
        }

        val userMessage = ChatMessage("user", mutableListOf(ContentBlock.Text(text)))
        val historyForRequest = _uiState.value.messages + userMessage
        _uiState.update { it.copy(messages = historyForRequest, input = "", isStreaming = true, error = null) }

        val client = AnthropicClient(_settings.value)
        viewModelScope.launch(Dispatchers.IO) {
            client.runConversation(historyForRequest, object : ConversationListener {
                override fun onUpdate(messages: List<ChatMessage>) {
                    _uiState.update { it.copy(messages = messages.toList()) }
                }
                override fun onToolCall(name: String, result: String) {
                    // Tool calls render inline via the message blocks themselves;
                    // this hook is left for future use (logging, a toast, etc).
                }
                override fun onError(message: String) {
                    _uiState.update { it.copy(error = message, isStreaming = false) }
                }
                override fun onComplete() {
                    _uiState.update { it.copy(isStreaming = false) }
                }
            })
        }
    }
}
