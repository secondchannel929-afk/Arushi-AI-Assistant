package com.example.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

class ArushiViewModel(application: Application) : AndroidViewModel(application) {
    val actionBridge = ActionBridge(application.applicationContext)
    val liveManager = GeminiLiveManager(
        context = application.applicationContext,
        actionBridge = actionBridge,
        scope = viewModelScope
    )

    val uiState: StateFlow<ArushiUiState> = liveManager.uiState

    fun updatePermissions(audio: Boolean, contacts: Boolean, call: Boolean) {
        liveManager.updatePermissions(audio, contacts, call)
    }

    fun startSession() {
        liveManager.startSession()
    }

    fun stopSession() {
        liveManager.stopSession()
    }

    fun toggleMute() {
        liveManager.toggleMute()
    }

    fun sendTextMessage(text: String) {
        liveManager.sendTextMessage(text)
    }

    fun saveCustomApiKey(key: String) {
        liveManager.saveCustomApiKey(key)
    }

    fun getSavedApiKey(): String {
        return liveManager.getSavedApiKey()
    }

    override fun onCleared() {
        super.onCleared()
        liveManager.cleanUp()
    }
}
