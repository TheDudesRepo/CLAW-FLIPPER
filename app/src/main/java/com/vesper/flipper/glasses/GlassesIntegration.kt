package com.vesper.flipper.glasses

import android.util.Base64
import android.util.Log
import com.vesper.flipper.ai.VesperAgent
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires the [GlassesBridgeClient] into V3SP3R's conversation pipeline.
 *
 * Handles:
 * - Voice transcriptions from glasses → VesperAgent as user messages
 * - Camera photos from glasses → VesperAgent as image attachments
 * - AI responses from VesperAgent → glasses for TTS + HUD display
 * - Flipper status events → glasses for HUD notifications
 * - "Hey Vesper" wake word commands → immediate execution
 * - Photo auto-upload to chat pending images for combo with voice/text
 */
@Singleton
class GlassesIntegration @Inject constructor(
    val bridge: GlassesBridgeClient,
    private val vesperAgent: VesperAgent,
    private val settingsStore: SettingsStore
) {
    companion object {
        private const val TAG = "GlassesIntegration"
        private const val PHOTO_HOLD_TIMEOUT_MS = 30_000L // 30s to combine photo with text/voice
    }

    val bridgeState: StateFlow<BridgeState> = bridge.state
    val incomingMessages: SharedFlow<GlassesMessage> = bridge.incomingMessages

    // Pending photo from glasses — exposed so ChatViewModel can show it in the input area
    private val _pendingGlassesPhoto = MutableStateFlow<ImageAttachment?>(null)
    val pendingGlassesPhoto: StateFlow<ImageAttachment?> = _pendingGlassesPhoto.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var messageListenerJob: Job? = null
    private var responseListenerJob: Job? = null
    private var lastProcessedMessageCount = 0
    private var photoHoldJob: Job? = null

    /**
     * Connect to the glasses bridge and start relaying messages.
     */
    fun connect(bridgeUrl: String) {
        bridge.connect(bridgeUrl)
        startListeners()
    }

    /**
     * Disconnect from the glasses bridge and stop all listeners.
     */
    fun disconnect() {
        stopListeners()
        bridge.disconnect()
    }

    fun isConnected(): Boolean = bridge.isConnected()

    /**
     * Start listening for incoming glasses messages and outgoing AI responses.
     */
    private fun startListeners() {
        stopListeners()

        // Listen for messages FROM glasses (voice, photos)
        messageListenerJob = scope.launch {
            bridge.incomingMessages.collect { message ->
                handleGlassesMessage(message)
            }
        }

        // Listen for AI responses TO send to glasses
        responseListenerJob = scope.launch {
            vesperAgent.conversationState.collect { state ->
                handleConversationUpdate(state)
            }
        }
    }

    private fun stopListeners() {
        messageListenerJob?.cancel()
        messageListenerJob = null
        responseListenerJob?.cancel()
        responseListenerJob = null
    }

    /**
     * Handle an incoming message from the glasses.
     */
    private suspend fun handleGlassesMessage(message: GlassesMessage) {
        val glassesEnabled = settingsStore.glassesEnabled.first()
        if (!glassesEnabled) return

        when (message.type) {
            MessageType.VOICE_TRANSCRIPTION -> handleVoiceTranscription(message)
            MessageType.CAMERA_PHOTO -> handleCameraPhoto(message)
            MessageType.VOICE_COMMAND -> handleVoiceCommand(message)
            else -> { /* Outbound message types — ignore */ }
        }
    }

    /**
     * Voice transcription from glasses mic → send as user message to VesperAgent.
     * Only processes final transcriptions (not partials).
     * If a glasses photo is pending, combines it with the voice text.
     */
    private suspend fun handleVoiceTranscription(message: GlassesMessage) {
        val text = message.text?.trim() ?: return
        if (text.isBlank()) return
        if (!message.isFinal) return // Skip partial transcriptions

        Log.i(TAG, "Glasses voice: \"$text\"")

        val autoSend = settingsStore.glassesAutoSend.first()
        if (autoSend) {
            sendWithPendingPhoto(text)
        }
        // If autoSend is off, the transcription still reaches the UI via incomingMessages
        // and the ChatViewModel can append it to the input field
    }

    /**
     * Camera photo from glasses → holds as pending image in chat input.
     *
     * The photo is exposed via [pendingGlassesPhoto] so the ChatViewModel can
     * add it to the pending images list. If no voice/text directive arrives
     * within [PHOTO_HOLD_TIMEOUT_MS], sends with a default prompt.
     */
    private suspend fun handleCameraPhoto(message: GlassesMessage) {
        val imageData = message.imageBase64 ?: return
        val mimeType = message.imageMimeType ?: "image/jpeg"
        val promptText = message.text

        Log.i(TAG, "Glasses camera: ${imageData.length} chars base64")

        val attachment = ImageAttachment(
            base64Data = imageData,
            mimeType = mimeType
        )

        // Cancel any previous hold timer
        photoHoldJob?.cancel()

        // Expose photo to UI as pending
        _pendingGlassesPhoto.value = attachment
        bridge.sendStatus("Photo received — say a command or type to combine")

        // Start a timeout: if no directive arrives, auto-send with default prompt
        photoHoldJob = scope.launch {
            delay(PHOTO_HOLD_TIMEOUT_MS)
            val stillPending = _pendingGlassesPhoto.value
            if (stillPending != null && stillPending.id == attachment.id) {
                Log.i(TAG, "Photo hold timed out — sending with default prompt")
                _pendingGlassesPhoto.value = null
                vesperAgent.sendMessage(
                    userMessage = promptText ?: "What am I looking at?",
                    imageAttachments = listOf(attachment)
                )
                bridge.sendStatus("Analyzing image...")
            }
        }
    }

    /**
     * Explicit voice command from "Hey Vesper" wake word.
     * Always auto-sends. Combines with pending photo if one exists.
     */
    private suspend fun handleVoiceCommand(message: GlassesMessage) {
        val text = message.text?.trim() ?: return
        if (text.isBlank()) return

        Log.i(TAG, "Glasses command: \"$text\"")
        sendWithPendingPhoto(text)
    }

    /**
     * Send a message to VesperAgent, attaching any pending glasses photo.
     * Clears the pending photo after sending.
     */
    private suspend fun sendWithPendingPhoto(text: String) {
        val photo = _pendingGlassesPhoto.value
        if (photo != null) {
            // Combine voice/text directive with the pending photo
            photoHoldJob?.cancel()
            _pendingGlassesPhoto.value = null
            Log.i(TAG, "Combining pending photo with directive: \"$text\"")
            vesperAgent.sendMessage(
                userMessage = text,
                imageAttachments = listOf(photo)
            )
            bridge.sendStatus("Processing with image: \"${text.take(40)}\"")
        } else {
            vesperAgent.sendMessage(userMessage = text)
            bridge.sendStatus("Processing: \"${text.take(50)}\"")
        }
    }

    /**
     * Clear the pending glasses photo (called by ChatViewModel when user
     * manually removes it from the input area).
     */
    fun clearPendingPhoto() {
        photoHoldJob?.cancel()
        _pendingGlassesPhoto.value = null
    }

    /**
     * Watch VesperAgent conversation state for new assistant messages
     * and relay them to glasses for TTS + HUD display.
     */
    private suspend fun handleConversationUpdate(state: ConversationState) {
        if (!bridge.isConnected()) return

        val messages = state.messages
        if (messages.size <= lastProcessedMessageCount || state.isLoading) {
            lastProcessedMessageCount = messages.size
            return
        }

        val lastMsg = messages.lastOrNull() ?: return
        if (lastMsg.role == MessageRole.ASSISTANT &&
            lastMsg.status == MessageStatus.COMPLETE &&
            lastMsg.content.isNotBlank() &&
            lastMsg.toolCalls.isNullOrEmpty()
        ) {
            bridge.sendResponse(lastMsg.content)
        }

        lastProcessedMessageCount = messages.size
    }

    fun destroy() {
        disconnect()
        clearPendingPhoto()
        scope.cancel()
        bridge.destroy()
    }
}
