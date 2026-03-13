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

        // Voice patterns for approving/denying Flipper operations hands-free
        private val APPROVE_PATTERNS = listOf(
            "yes", "approve", "confirm", "do it", "go ahead",
            "execute", "proceed", "affirmative", "yep", "yeah"
        )
        private val DENY_PATTERNS = listOf(
            "no", "deny", "reject", "cancel", "stop",
            "abort", "negative", "nope", "don't", "do not"
        )
    }

    val bridgeState: StateFlow<BridgeState> = bridge.state
    val incomingMessages: SharedFlow<GlassesMessage> = bridge.incomingMessages

    // Pending photo from glasses — exposed so ChatViewModel can show it in the input area
    private val _pendingGlassesPhoto = MutableStateFlow<ImageAttachment?>(null)
    val pendingGlassesPhoto: StateFlow<ImageAttachment?> = _pendingGlassesPhoto.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var messageListenerJob: Job? = null
    private var responseListenerJob: Job? = null
    private var progressListenerJob: Job? = null
    private var approvalListenerJob: Job? = null
    private var lastProcessedMessageCount = 0
    private var photoHoldJob: Job? = null

    // Track whether we're waiting for a voice approval so we can intercept yes/no
    @Volatile
    private var awaitingVoiceApproval = false
    private var pendingApprovalId: String? = null
    private var lastSpokenProgressStage: AgentProgressStage? = null

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

        // Listen for AI responses + progress TO send to glasses
        responseListenerJob = scope.launch {
            vesperAgent.conversationState.collect { state ->
                handleConversationUpdate(state)
            }
        }

        // Listen for agent progress updates → narrate on glasses
        progressListenerJob = scope.launch {
            vesperAgent.conversationState
                .map { it.progress }
                .distinctUntilChanged()
                .collect { progress ->
                    if (progress != null && bridge.isConnected()) {
                        handleProgressUpdate(progress)
                    }
                }
        }

        // Listen for approval requests → speak on glasses
        approvalListenerJob = scope.launch {
            vesperAgent.conversationState
                .map { it.pendingApproval }
                .distinctUntilChanged()
                .collect { approval ->
                    if (approval != null && bridge.isConnected()) {
                        handleApprovalRequest(approval)
                    }
                }
        }
    }

    private fun stopListeners() {
        messageListenerJob?.cancel()
        messageListenerJob = null
        responseListenerJob?.cancel()
        responseListenerJob = null
        progressListenerJob?.cancel()
        progressListenerJob = null
        approvalListenerJob?.cancel()
        approvalListenerJob = null
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
     * Intercepts yes/no when waiting for approval.
     * If a glasses photo is pending, combines it with the voice text.
     */
    private suspend fun handleVoiceTranscription(message: GlassesMessage) {
        val text = message.text?.trim() ?: return
        if (text.isBlank()) return
        if (!message.isFinal) return // Skip partial transcriptions

        Log.i(TAG, "Glasses voice: \"$text\"")

        // Intercept approval responses even on passive transcriptions
        if (awaitingVoiceApproval && pendingApprovalId != null) {
            val lowerText = text.lowercase()
            when {
                APPROVE_PATTERNS.any { lowerText.contains(it) } -> {
                    Log.i(TAG, "Voice approval (transcription): \"$text\"")
                    awaitingVoiceApproval = false
                    val approvalId = pendingApprovalId!!
                    pendingApprovalId = null
                    bridge.sendStatus("Approved — executing")
                    vesperAgent.continueAfterApproval(approvalId, approved = true)
                    return
                }
                DENY_PATTERNS.any { lowerText.contains(it) } -> {
                    Log.i(TAG, "Voice denial (transcription): \"$text\"")
                    awaitingVoiceApproval = false
                    val approvalId = pendingApprovalId!!
                    pendingApprovalId = null
                    bridge.sendStatus("Denied — cancelled")
                    vesperAgent.continueAfterApproval(approvalId, approved = false)
                    return
                }
            }
        }

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
     * Intercepts approval responses (yes/no) when waiting for confirmation.
     */
    private suspend fun handleVoiceCommand(message: GlassesMessage) {
        val text = message.text?.trim() ?: return
        if (text.isBlank()) return

        // Check if this is a voice approval/rejection
        if (awaitingVoiceApproval && pendingApprovalId != null) {
            val lowerText = text.lowercase()
            when {
                APPROVE_PATTERNS.any { lowerText.contains(it) } -> {
                    Log.i(TAG, "Voice approval: \"$text\"")
                    awaitingVoiceApproval = false
                    val approvalId = pendingApprovalId!!
                    pendingApprovalId = null
                    bridge.sendStatus("Approved — executing")
                    vesperAgent.continueAfterApproval(approvalId, approved = true)
                    return
                }
                DENY_PATTERNS.any { lowerText.contains(it) } -> {
                    Log.i(TAG, "Voice denial: \"$text\"")
                    awaitingVoiceApproval = false
                    val approvalId = pendingApprovalId!!
                    pendingApprovalId = null
                    bridge.sendStatus("Denied — cancelled")
                    vesperAgent.continueAfterApproval(approvalId, approved = false)
                    return
                }
            }
            // Not a yes/no — fall through to normal command handling
        }

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
     * Final responses (no tool calls) are spoken aloud through the glasses.
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
            // Final response — speak it through glasses
            bridge.sendResponse(lastMsg.content)
            awaitingVoiceApproval = false
            pendingApprovalId = null
        }

        lastProcessedMessageCount = messages.size
    }

    /**
     * Narrate agent progress through glasses so the user hears what's happening.
     * Only speaks key transitions, not every micro-update.
     */
    private fun handleProgressUpdate(progress: AgentProgress) {
        // Avoid repeating the same stage
        if (progress.stage == lastSpokenProgressStage) return
        lastSpokenProgressStage = progress.stage

        val narration = when (progress.stage) {
            AgentProgressStage.MODEL_REQUEST -> "Thinking..."
            AgentProgressStage.TOOL_PLANNED -> progress.detail ?: "Planning actions..."
            AgentProgressStage.TOOL_EXECUTING -> progress.detail ?: "Executing on Flipper..."
            AgentProgressStage.TOOL_COMPLETED -> progress.detail ?: "Action complete."
            AgentProgressStage.WAITING_APPROVAL -> return // Handled separately in handleApprovalRequest
        }

        Log.d(TAG, "Glasses narration: $narration")
        bridge.sendStatus(narration)
    }

    /**
     * Speak an approval request through the glasses so the user can
     * approve or deny hands-free with voice ("yes"/"no"/"approve"/"deny").
     */
    private fun handleApprovalRequest(approval: PendingApproval) {
        val command = approval.command
        val risk = approval.riskAssessment
        val actionName = command.action.name.lowercase().replace('_', ' ')
        val path = command.args.path ?: command.args.destinationPath ?: ""

        val spokenPrompt = buildString {
            append("Approval needed. ")
            append("${risk.level.name.lowercase()} risk. ")
            append("$actionName")
            if (path.isNotBlank()) append(" on $path")
            append(". ${risk.reason}. ")
            append("Say yes to approve, or no to deny.")
        }

        Log.i(TAG, "Glasses approval prompt: $spokenPrompt")

        // Arm voice approval interception
        awaitingVoiceApproval = true
        pendingApprovalId = approval.id

        // Speak + display on glasses
        bridge.sendResponse(
            text = spokenPrompt,
            displayText = "⚠ ${risk.level}: $actionName ${path.takeLast(30)}\nSay YES or NO"
        )
    }

    fun destroy() {
        disconnect()
        clearPendingPhoto()
        scope.cancel()
        bridge.destroy()
    }
}
