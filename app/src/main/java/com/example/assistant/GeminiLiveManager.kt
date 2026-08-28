package com.example.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private const val TAG = "GeminiLiveManager"
private const val SAMPLE_RATE_IN = 16000
private const val SAMPLE_RATE_OUT = 24000
private const val PREFS_NAME = "arushi_prefs"
private const val KEY_CUSTOM_API_KEY = "custom_gemini_api_key"

class GeminiLiveManager(
    private val context: Context,
    private val actionBridge: ActionBridge,
    private val scope: CoroutineScope
) : TextToSpeech.OnInitListener {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(ArushiUiState())
    val uiState: StateFlow<ArushiUiState> = _uiState.asStateFlow()

    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // Audio recording for WebSocket Live
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    // Audio playback for WebSocket Live
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val audioPlaybackQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var isPlayingAudio = false

    // Android Speech Recognizer & TTS Fallback
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var isUsingFallbackMode = false

    init {
        initAudioTrack()
        initTextToSpeech()
        val key = getActiveApiKey()
        _uiState.value = _uiState.value.copy(
            isApiKeyConfigured = key.isNotBlank(),
            statusMessage = if (key.isNotBlank()) "Tap to talk with Arushi" else "Configure Gemini API Key"
        )
    }

    private fun getActiveApiKey(): String {
        val customKey = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
        if (customKey.isNotBlank()) return customKey.trim()
        val buildKey = BuildConfig.GEMINI_API_KEY
        if (buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY") return buildKey.trim()
        return ""
    }

    fun saveCustomApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString(KEY_CUSTOM_API_KEY, trimmed).apply()
        val isConfigured = trimmed.isNotBlank()
        _uiState.value = _uiState.value.copy(
            isApiKeyConfigured = isConfigured,
            connectionState = LiveConnectionState.DISCONNECTED,
            statusMessage = if (isConfigured) "API Key saved! Tap to talk." else "Please configure API Key."
        )
        if (isConfigured) {
            addMessage(MessageRole.SYSTEM, "Google Gemini API Key configured! Arushi is ready to talk and answer all your questions.")
        }
    }

    fun getSavedApiKey(): String {
        return prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_OUT,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_OUT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            startPlaybackLoop()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init error: ${e.message}")
        }
    }

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(context, this)
        } catch (e: Exception) {
            Log.e(TAG, "TextToSpeech init error", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            textToSpeech?.language = Locale.forLanguageTag("hi-IN")
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = LiveConnectionState.SPEAKING
                    )
                }

                override fun onDone(utteranceId: String?) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = LiveConnectionState.LISTENING,
                        statusMessage = "Listening..."
                    )
                    if (isUsingFallbackMode && !isRecording) {
                        scope.launch(Dispatchers.Main) {
                            startSpeechRecognition()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = LiveConnectionState.LISTENING
                    )
                }
            })
        }
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            for (chunk in audioPlaybackQueue) {
                if (!isActive) break
                try {
                    if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.play()
                    }
                    isPlayingAudio = true
                    _uiState.value = _uiState.value.copy(
                        connectionState = LiveConnectionState.SPEAKING
                    )
                    audioTrack?.write(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing audio chunk", e)
                }
            }
            isPlayingAudio = false
        }
    }

    fun updatePermissions(audio: Boolean, contacts: Boolean, call: Boolean) {
        _uiState.value = _uiState.value.copy(
            hasAudioPermission = audio,
            hasContactsPermission = contacts,
            hasCallPermission = call
        )
    }

    fun startSession() {
        val apiKey = getActiveApiKey()
        if (apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(
                isApiKeyConfigured = false,
                statusMessage = "Please add your GEMINI_API_KEY in Settings or Secrets",
                connectionState = LiveConnectionState.ERROR
            )
            addMessage(MessageRole.SYSTEM, "GEMINI_API_KEY is not configured. Click the Settings icon at the top to enter your API key, or use Secrets in AI Studio.")
            return
        }

        _uiState.value = _uiState.value.copy(
            connectionState = LiveConnectionState.CONNECTING,
            statusMessage = "Connecting to Arushi..."
        )

        // Try Gemini WebSocket Live API
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        webSocket?.cancel()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Live connected successfully.")
                isUsingFallbackMode = false
                scope.launch {
                    _uiState.value = _uiState.value.copy(
                        connectionState = LiveConnectionState.CONNECTED,
                        statusMessage = "Arushi Live Connected. Speak now!"
                    )
                    sendSetupMessage()
                    delay(300)
                    startRecording()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    handleIncomingJson(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch {
                    handleIncomingJson(bytes.utf8())
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $reason ($code)")
                stopRecording()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Live WebSocket unavailable (${t.message}), activating REST AI & Voice engine.")
                // Seamlessly fallback to REST + Speech Engine
                scope.launch(Dispatchers.Main) {
                    activateFallbackMode()
                }
            }
        })
    }

    private fun activateFallbackMode() {
        isUsingFallbackMode = true
        _uiState.value = _uiState.value.copy(
            connectionState = LiveConnectionState.LISTENING,
            statusMessage = "Arushi Voice Assistant Active (Listening...)"
        )
        startSpeechRecognition()
    }

    private fun startSpeechRecognition() {
        if (_uiState.value.isMicMuted) return
        scope.launch(Dispatchers.Main) {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                    putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, "hi-IN,en-IN,en-US")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _uiState.value = _uiState.value.copy(
                            connectionState = LiveConnectionState.LISTENING,
                            statusMessage = "Listening... Speak in Hindi, English, Hinglish..."
                        )
                    }

                    override fun onBeginningOfSpeech() {
                        _uiState.value = _uiState.value.copy(
                            audioVolumeLevel = 0.6f
                        )
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        val norm = (rmsdB / 10f).coerceIn(0f, 1f)
                        _uiState.value = _uiState.value.copy(audioVolumeLevel = norm)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        _uiState.value = _uiState.value.copy(audioVolumeLevel = 0f)
                    }

                    override fun onError(error: Int) {
                        Log.d(TAG, "SpeechRecognizer error: $error")
                        _uiState.value = _uiState.value.copy(audioVolumeLevel = 0f)
                        // If user is silent, restart recognition after a brief pause
                        if (isUsingFallbackMode && _uiState.value.connectionState != LiveConnectionState.DISCONNECTED) {
                            scope.launch {
                                delay(1200)
                                if (isUsingFallbackMode) startSpeechRecognition()
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            sendTextMessage(text)
                        } else if (isUsingFallbackMode) {
                            startSpeechRecognition()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            _uiState.value = _uiState.value.copy(currentUserSpeech = text)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting SpeechRecognizer", e)
            }
        }
    }

    fun stopSession() {
        stopRecording()
        stopAudioPlayback()
        textToSpeech?.stop()
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        webSocket?.close(1000, "User ended session")
        webSocket = null
        isUsingFallbackMode = false
        _uiState.value = _uiState.value.copy(
            connectionState = LiveConnectionState.DISCONNECTED,
            statusMessage = "Session stopped. Tap mic to talk."
        )
    }

    fun toggleMute() {
        val current = _uiState.value.isMicMuted
        _uiState.value = _uiState.value.copy(isMicMuted = !current)
        if (!current) {
            _uiState.value = _uiState.value.copy(statusMessage = "Microphone muted")
            speechRecognizer?.stopListening()
        } else {
            _uiState.value = _uiState.value.copy(statusMessage = "Listening...")
            if (isUsingFallbackMode) {
                startSpeechRecognition()
            }
        }
    }

    private fun sendSetupMessage() {
        try {
            val setupObj = JSONObject().apply {
                val setup = JSONObject().apply {
                    put("model", "models/gemini-2.0-flash-exp")

                    val generationConfig = JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        val speechConfig = JSONObject().apply {
                            val voiceConfig = JSONObject().apply {
                                val prebuilt = JSONObject().apply {
                                    put("voiceName", "Aoede")
                                }
                                put("prebuiltVoiceConfig", prebuilt)
                            }
                            put("voiceConfig", voiceConfig)
                        }
                        put("speechConfig", speechConfig)
                    }
                    put("generationConfig", generationConfig)

                    val systemInstruction = JSONObject().apply {
                        val parts = JSONArray().put(
                            JSONObject().put(
                                "text",
                                """
                                You are Arushi, an intelligent, empathetic multilingual Indian voice assistant.
                                Natively understand and speak Hindi, English, Hinglish, Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, Urdu, and other languages.
                                - Automatically detect language.
                                - If user speaks Hindi, reply in Hindi.
                                - If English, reply in English.
                                - If Hinglish, reply naturally in Hinglish.
                                - Switch dynamically mid-conversation.
                                - Execute function calling tools whenever user asks to open WhatsApp, call contact, make phone call, or open apps.
                                """.trimIndent()
                            )
                        )
                        put("parts", parts)
                    }
                    put("systemInstruction", systemInstruction)

                    // Tools definitions
                    val tools = JSONArray().put(
                        JSONObject().apply {
                            val functionDeclarations = JSONArray()

                            functionDeclarations.put(JSONObject().apply {
                                put("name", "openWhatsApp")
                                put("description", "Opens WhatsApp application on the phone. Triggered by 'Open WhatsApp', 'WhatsApp kholo', 'WhatsApp open karo', 'WhatsApp chalao', etc.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject())
                                })
                            })

                            functionDeclarations.put(JSONObject().apply {
                                put("name", "openApp")
                                put("description", "Opens an installed app or system settings by name (e.g. 'Instagram', 'Chrome', 'Settings', 'YouTube', 'Camera', 'Calculator', 'Maps', 'Spotify').")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("appName", JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "Name of the app, e.g. 'Instagram', 'Chrome', 'Settings', 'YouTube'")
                                        })
                                    })
                                    put("required", JSONArray().put("appName"))
                                })
                            })

                            functionDeclarations.put(JSONObject().apply {
                                put("name", "makeCall")
                                put("description", "Initiates a phone call to a numeric phone number.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("phoneNumber", JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "Phone number string, e.g. '9876543210'")
                                        })
                                    })
                                    put("required", JSONArray().put("phoneNumber"))
                                })
                            })

                            functionDeclarations.put(JSONObject().apply {
                                put("name", "callContact")
                                put("description", "Searches device contacts by name or relationship (e.g., 'Mom', 'Mummy', 'Rahul', 'Dad') and calls them.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("contactName", JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "Contact name to search, e.g. 'Mom', 'Mummy', 'Rahul', 'Dad'")
                                        })
                                    })
                                    put("required", JSONArray().put("contactName"))
                                })
                            })

                            functionDeclarations.put(JSONObject().apply {
                                put("name", "openUrl")
                                put("description", "Opens a web link.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("url", JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "Web URL")
                                        })
                                    })
                                    put("required", JSONArray().put("url"))
                                })
                            })

                            put("functionDeclarations", functionDeclarations)
                        }
                    )
                    put("tools", tools)
                }
                put("setup", setup)
            }

            webSocket?.send(setupObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error in setup", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) return
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                activateFallbackMode()
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            _uiState.value = _uiState.value.copy(
                connectionState = LiveConnectionState.LISTENING,
                statusMessage = "Listening... Speak in any language"
            )

            recordingJob?.cancel()
            recordingJob = scope.launch(Dispatchers.IO) {
                val audioBuffer = ByteArray(2048)
                while (isActive && isRecording) {
                    val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readBytes > 0) {
                        var sum = 0.0
                        var count = 0
                        var i = 0
                        while (i < readBytes - 1) {
                            val sample = (audioBuffer[i].toInt() and 0xFF) or (audioBuffer[i + 1].toInt() shl 8)
                            sum += sample * sample
                            count++
                            i += 2
                        }
                        val rms = if (count > 0) sqrt(sum / count).toFloat() else 0f
                        val normalizedVol = (rms / 32768f * 4.5f).coerceIn(0f, 1f)

                        if (isPlayingAudio && normalizedVol > 0.35f) {
                            stopAudioPlayback()
                        }

                        _uiState.value = _uiState.value.copy(
                            audioVolumeLevel = if (_uiState.value.isMicMuted) 0f else normalizedVol
                        )

                        if (!_uiState.value.isMicMuted && webSocket != null) {
                            val base64Data = Base64.encodeToString(
                                audioBuffer,
                                0,
                                readBytes,
                                Base64.NO_WRAP
                            )
                            sendAudioChunk(base64Data)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord error: ${e.message}")
            activateFallbackMode()
        }
    }

    private fun sendAudioChunk(base64Pcm: String) {
        try {
            val json = JSONObject().apply {
                val realtimeInput = JSONObject().apply {
                    val mediaChunks = JSONArray().put(
                        JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Pcm)
                        }
                    )
                    put("mediaChunks", mediaChunks)
                }
                put("realtimeInput", realtimeInput)
            }
            webSocket?.send(json.toString())
        } catch (_: Exception) {}
    }

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        addMessage(MessageRole.USER, text)
        _uiState.value = _uiState.value.copy(
            connectionState = LiveConnectionState.THINKING,
            statusMessage = "Arushi is thinking..."
        )

        // If WebSocket is connected, send through WebSocket
        if (webSocket != null && !isUsingFallbackMode) {
            try {
                val json = JSONObject().apply {
                    val clientContent = JSONObject().apply {
                        val turns = JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                val parts = JSONArray().put(
                                    JSONObject().put("text", text)
                                )
                                put("parts", parts)
                            }
                        )
                        put("turns", turns)
                        put("turnComplete", true)
                    }
                    put("clientContent", clientContent)
                }
                webSocket?.send(json.toString())
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error sending WebSocket message", e)
            }
        }

        // Otherwise, process through Gemini REST API + Action Bridge & TTS
        scope.launch(Dispatchers.IO) {
            processMessageWithGeminiRest(text)
        }
    }

    private suspend fun processMessageWithGeminiRest(userText: String) {
        val apiKey = getActiveApiKey()

        // 1. Check direct tool triggers immediately for instant local execution
        val lower = userText.lowercase().trim()
        val directToolHandled = checkLocalDirectCommand(userText, lower)
        if (directToolHandled != null) {
            speakFallbackResponse(directToolHandled)
            return
        }

        if (apiKey.isBlank()) {
            val fallbackResp = "Please configure your Google Gemini API Key in Settings to chat with Arushi."
            speakFallbackResponse(fallbackResp)
            return
        }

        // Call Gemini REST API with Function Calling
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        try {
            val requestBodyJson = JSONObject().apply {
                val contents = JSONArray()
                // Format clean alternating turns for Gemini REST
                val validMessages = _uiState.value.messages
                    .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                    .takeLast(8)

                var lastTurnRole: String? = null
                for (m in validMessages) {
                    val turnRole = if (m.role == MessageRole.USER) "user" else "model"
                    if (turnRole != lastTurnRole && m.text.isNotBlank()) {
                        contents.put(JSONObject().apply {
                            put("role", turnRole)
                            put("parts", JSONArray().put(JSONObject().put("text", m.text)))
                        })
                        lastTurnRole = turnRole
                    }
                }

                // If contents is empty or last was not user, ensure the current question is added
                if (contents.length() == 0 || lastTurnRole != "user") {
                    contents.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", userText)))
                    })
                }
                put("contents", contents)

                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put(
                        "text",
                        """
                        You are Arushi, an intelligent, empathetic multilingual Indian voice and chat AI assistant.
                        You natively understand and speak Hindi, English, Hinglish, Marathi, Bengali, Tamil, Telugu, Gujarati, Punjabi, Kannada, Malayalam, and Urdu.
                        - Always answer the user's questions clearly, accurately, concisely and politely.
                        - Answer in Hindi if asked in Hindi, English if asked in English, or natural Hinglish.
                        - Execute functions whenever user asks to open WhatsApp, call contacts, make calls, or open apps.
                        """.trimIndent()
                    )))
                })

                // Functions
                put("tools", JSONArray().put(
                    JSONObject().apply {
                        val fns = JSONArray()
                        fns.put(JSONObject().apply {
                            put("name", "openWhatsApp")
                            put("description", "Opens WhatsApp on the phone.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })
                        fns.put(JSONObject().apply {
                            put("name", "openApp")
                            put("description", "Opens an app or settings by name.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("appName", JSONObject().put("type", "STRING"))
                                })
                                put("required", JSONArray().put("appName"))
                            })
                        })
                        fns.put(JSONObject().apply {
                            put("name", "makeCall")
                            put("description", "Makes a phone call to a numeric phone number.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("phoneNumber", JSONObject().put("type", "STRING"))
                                })
                                put("required", JSONArray().put("phoneNumber"))
                            })
                        })
                        fns.put(JSONObject().apply {
                            put("name", "callContact")
                            put("description", "Calls a contact by name or relationship (Mom, Mummy, Rahul, Dad).")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("contactName", JSONObject().put("type", "STRING"))
                                })
                                put("required", JSONArray().put("contactName"))
                            })
                        })
                        put("functionDeclarations", fns)
                    }
                ))
            }

            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val root = JSONObject(responseBody)
                val candidates = root.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")

                    var replyText = ""
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("functionCall")) {
                                val fc = part.getJSONObject("functionCall")
                                val fnName = fc.optString("name", "")
                                val args = fc.optJSONObject("args") ?: JSONObject()
                                executeLocalToolAndReply(fnName, args)
                                return
                            }
                            if (part.has("text")) {
                                replyText += part.optString("text", "")
                            }
                        }
                    }

                    if (replyText.isNotBlank()) {
                        speakFallbackResponse(replyText)
                    } else {
                        speakFallbackResponse("Haan ji, main aapki kya madad kar sakti hoon?")
                    }
                } else {
                    speakFallbackResponse("Aapki baat samajh nahi aayi, kripya dobara bolein.")
                }
            } else {
                Log.w(TAG, "Gemini REST API error: ${response.code} $responseBody")
                if (response.code == 400 || response.code == 403 || response.code == 401) {
                    _uiState.value = _uiState.value.copy(
                        isApiKeyConfigured = false,
                        connectionState = LiveConnectionState.ERROR,
                        statusMessage = "Invalid or expired Gemini API Key (${response.code})"
                    )
                    speakFallbackResponse("Aapki Gemini API Key invalid hai. Kripya Settings mein sahi Google Gemini API Key enter karein.")
                } else {
                    val localResult = checkLocalDirectCommand(userText, lower)
                    if (localResult != null) {
                        speakFallbackResponse(localResult)
                    } else {
                        speakFallbackResponse("Server response error (${response.code}). Kripya dobara try karein.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in Gemini REST call", e)
            val localResult = checkLocalDirectCommand(userText, lower)
            if (localResult != null) {
                speakFallbackResponse(localResult)
            } else {
                speakFallbackResponse("Maaf kijiye, network samasya ke kaaran connect nahi ho paya. Kripya apna internet aur API key check karein.")
            }
        }
    }

    private suspend fun executeLocalToolAndReply(fnName: String, args: JSONObject) {
        val actionResult: ActionResult = withContext(Dispatchers.Main) {
            when (fnName) {
                "openWhatsApp" -> actionBridge.openWhatsApp()
                "openApp" -> actionBridge.openApp(args.optString("appName", "WhatsApp"))
                "makeCall" -> actionBridge.makeCall(args.optString("phoneNumber", ""))
                "callContact" -> actionBridge.callContact(args.optString("contactName", ""))
                "openUrl" -> actionBridge.openUrl(args.optString("url", "https://google.com"))
                else -> ActionResult(false, fnName, "Command not recognized")
            }
        }

        _uiState.value = _uiState.value.copy(
            lastActionResult = actionResult,
            statusMessage = actionResult.message
        )
        addMessage(MessageRole.ACTION, actionResult.message, fnName, actionResult.success)

        val voiceMessage = when (fnName) {
            "openWhatsApp" -> if (actionResult.success) "WhatsApp open kar diya hai." else "WhatsApp open nahi ho paya."
            "openApp" -> if (actionResult.success) "${args.optString("appName")} open kar diya hai." else "${args.optString("appName")} nahi mila."
            "makeCall" -> if (actionResult.success) "${args.optString("phoneNumber")} par call lagayi ja rahi hai." else "Call nahi lag payi."
            "callContact" -> actionResult.message
            else -> actionResult.message
        }
        speakFallbackResponse(voiceMessage)
    }

    private suspend fun checkLocalDirectCommand(raw: String, lower: String): String? {
        // WhatsApp commands
        if (lower.contains("whatsapp") || lower.contains("whats app")) {
            val res = withContext(Dispatchers.Main) { actionBridge.openWhatsApp() }
            _uiState.value = _uiState.value.copy(lastActionResult = res)
            addMessage(MessageRole.ACTION, res.message, "openWhatsApp", res.success)
            return if (res.success) "WhatsApp open kar diya hai." else "WhatsApp open nahi ho saka."
        }

        // Call numeric digits
        val digitsMatch = Regex("(call|phone|dial|lagao)\\s+([0-9+]{6,15})").find(lower)
            ?: Regex("([0-9+]{10,13})\\s*(ko|par)?\\s*(call|phone)").find(lower)
        if (digitsMatch != null) {
            val phone = digitsMatch.groupValues.firstOrNull { it.matches(Regex("[0-9+]{6,15}")) } ?: ""
            if (phone.isNotBlank()) {
                val res = withContext(Dispatchers.Main) { actionBridge.makeCall(phone) }
                _uiState.value = _uiState.value.copy(lastActionResult = res)
                addMessage(MessageRole.ACTION, res.message, "makeCall", res.success)
                return "$phone par call connect ki ja rahi hai."
            }
        }

        // Call contact name
        val contactMatch = Regex("(call|phone|lagao)\\s+(mom|mummy|mother|maa|dad|papa|father|rahul|pooja|priya|bhai|didi|[a-zA-Z]+)", RegexOption.IGNORE_CASE).find(raw)
            ?: Regex("([a-zA-Z]+)\\s+ko\\s+(call|phone|phone lagao)", RegexOption.IGNORE_CASE).find(raw)
        if (contactMatch != null) {
            val name = contactMatch.groupValues[2].ifBlank { contactMatch.groupValues[1] }
            if (name.isNotBlank() && !name.equals("me", ignoreCase = true) && !name.equals("to", ignoreCase = true)) {
                val res = withContext(Dispatchers.Main) { actionBridge.callContact(name) }
                _uiState.value = _uiState.value.copy(lastActionResult = res)
                addMessage(MessageRole.ACTION, res.message, "callContact", res.success)
                return res.message
            }
        }

        // Open App (Instagram, Settings, Chrome, etc.)
        if (lower.startsWith("open ") || lower.endsWith(" kholo") || lower.endsWith(" open karo") || lower.endsWith(" chalao")) {
            val app = lower.replace("open ", "").replace(" kholo", "").replace(" open karo", "").replace(" chalao", "").trim()
            if (app.isNotBlank()) {
                val res = withContext(Dispatchers.Main) { actionBridge.openApp(app) }
                _uiState.value = _uiState.value.copy(lastActionResult = res)
                addMessage(MessageRole.ACTION, res.message, "openApp", res.success)
                return if (res.success) "$app open kar diya hai." else "$app nahi mil saka."
            }
        }

        // Language switches
        if (lower.contains("hindi mein") || lower.contains("hindi me") || lower.contains("speak in hindi")) {
            return "Namaste! Ab se hum Hindi mein baat karenge. Main aapki kya madad kar sakti hoon?"
        }
        if (lower.contains("english") || lower.contains("talk in english")) {
            return "Hello! I have switched to English. How can I assist you today?"
        }
        if (lower.contains("hinglish")) {
            return "Arrey bilkul! Ab hum Hinglish mein baat karenge. Batao kya karna hai?"
        }
        if (lower.contains("hello arushi") || lower.contains("hi arushi") || lower == "arushi") {
            return "Namaste! Main Arushi hoon. Aapki kya sahayata kar sakti hoon?"
        }

        return null
    }

    private suspend fun speakFallbackResponse(text: String) {
        val detected = detectLanguageSimple(text)
        _uiState.value = _uiState.value.copy(
            currentAssistantSpeech = text,
            detectedLanguage = detected,
            connectionState = LiveConnectionState.SPEAKING,
            statusMessage = text
        )
        addMessage(MessageRole.ASSISTANT, text, language = detected)

        withContext(Dispatchers.Main) {
            if (isTtsInitialized && textToSpeech != null) {
                // Set appropriate TTS language
                val locale = when (detected) {
                    "English" -> Locale.forLanguageTag("en-IN")
                    else -> Locale.forLanguageTag("hi-IN")
                }
                textToSpeech?.language = locale
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arushi_utterance_${System.currentTimeMillis()}")
            } else {
                delay(2000)
                _uiState.value = _uiState.value.copy(
                    connectionState = LiveConnectionState.LISTENING,
                    statusMessage = "Listening..."
                )
            }
        }
    }

    private suspend fun handleIncomingJson(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)

            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                if (serverContent.optBoolean("interrupted", false)) {
                    stopAudioPlayback()
                    _uiState.value = _uiState.value.copy(
                        connectionState = LiveConnectionState.LISTENING,
                        statusMessage = "Listening..."
                    )
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val dataBase64 = inlineData.optString("data", "")
                                if (dataBase64.isNotEmpty()) {
                                    val pcmBytes = Base64.decode(dataBase64, Base64.DEFAULT)
                                    audioPlaybackQueue.send(pcmBytes)
                                }
                            }
                            if (part.has("text")) {
                                val text = part.optString("text", "")
                                if (text.isNotEmpty()) {
                                    handleReceivedText(text)
                                }
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = LiveConnectionState.LISTENING,
                        statusMessage = "Listening..."
                    )
                }
            }

            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                handleToolCall(toolCall)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming JSON", e)
        }
    }

    private fun handleReceivedText(text: String) {
        val current = _uiState.value.currentAssistantSpeech + text
        _uiState.value = _uiState.value.copy(currentAssistantSpeech = current)
        val detected = detectLanguageSimple(current)
        _uiState.value = _uiState.value.copy(detectedLanguage = detected)

        val messages = _uiState.value.messages.toMutableList()
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && (System.currentTimeMillis() - lastMsg.timestamp < 4000)) {
            messages[messages.size - 1] = lastMsg.copy(text = lastMsg.text + text, language = detected)
        } else {
            messages.add(ChatMessage(role = MessageRole.ASSISTANT, text = text, language = detected))
        }
        _uiState.value = _uiState.value.copy(messages = messages)
    }

    private suspend fun handleToolCall(toolCall: JSONObject) {
        val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
        val functionResponses = JSONArray()

        for (i in 0 until functionCalls.length()) {
            val call = functionCalls.getJSONObject(i)
            val callId = call.optString("id", "call_${System.currentTimeMillis()}")
            val name = call.optString("name", "")
            val args = call.optJSONObject("args") ?: JSONObject()

            val actionResult: ActionResult = withContext(Dispatchers.Main) {
                when (name) {
                    "openWhatsApp" -> actionBridge.openWhatsApp()
                    "openApp" -> actionBridge.openApp(args.optString("appName", "WhatsApp"))
                    "makeCall" -> actionBridge.makeCall(args.optString("phoneNumber", ""))
                    "callContact" -> actionBridge.callContact(args.optString("contactName", ""))
                    "openUrl" -> actionBridge.openUrl(args.optString("url", "https://google.com"))
                    else -> ActionResult(false, name, "Unknown tool call $name")
                }
            }

            _uiState.value = _uiState.value.copy(
                lastActionResult = actionResult,
                statusMessage = actionResult.message
            )

            addMessage(
                MessageRole.ACTION,
                actionResult.message,
                actionName = actionResult.actionName,
                actionSuccess = actionResult.success
            )

            val respObj = JSONObject().apply {
                put("id", callId)
                put("name", name)
                val response = JSONObject().apply {
                    val output = JSONObject().apply {
                        put("success", actionResult.success)
                        put("action", actionResult.actionName)
                        put("message", actionResult.message)
                        val keys = actionResult.details.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            put(k, actionResult.details.get(k))
                        }
                    }
                    put("output", output)
                }
                put("response", response)
            }
            functionResponses.put(respObj)
        }

        try {
            val toolRespMsg = JSONObject().apply {
                val toolResponse = JSONObject().apply {
                    put("functionResponses", functionResponses)
                }
                put("toolResponse", toolResponse)
            }
            webSocket?.send(toolRespMsg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending tool response", e)
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        _uiState.value = _uiState.value.copy(audioVolumeLevel = 0f)
    }

    private fun stopAudioPlayback() {
        try {
            while (audioPlaybackQueue.tryReceive().isSuccess) {}
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
        isPlayingAudio = false
    }

    private fun addMessage(role: MessageRole, text: String, actionName: String? = null, actionSuccess: Boolean? = null, language: String? = null) {
        val msg = ChatMessage(
            role = role,
            text = text,
            actionName = actionName,
            actionSuccess = actionSuccess,
            language = language
        )
        val list = _uiState.value.messages.toMutableList().apply { add(msg) }
        _uiState.value = _uiState.value.copy(messages = list)
    }

    private fun detectLanguageSimple(text: String): String {
        val devanagariCount = text.count { it in '\u0900'..'\u097F' }
        val bengaliCount = text.count { it in '\u0980'..'\u09FF' }
        val gurmukhiCount = text.count { it in '\u0A00'..'\u0A7F' }
        val gujaratiCount = text.count { it in '\u0A80'..'\u0AFF' }
        val tamilCount = text.count { it in '\u0B80'..'\u0BFF' }
        val teluguCount = text.count { it in '\u0C00'..'\u0C7F' }
        val kannadaCount = text.count { it in '\u0C80'..'\u0CFF' }
        val malayalamCount = text.count { it in '\u0D00'..'\u0D7F' }
        val arabicCount = text.count { it in '\u0600'..'\u06FF' }

        val lower = text.lowercase()
        val isHinglish = lower.contains("karo") || lower.contains("kholo") || lower.contains("hai") ||
                lower.contains("mein") || lower.contains("baat") || lower.contains("lagao") ||
                lower.contains("kaise") || lower.contains("chalao") || lower.contains("karen") ||
                lower.contains("diya") || lower.contains("nahi") || lower.contains("batao")

        return when {
            devanagariCount > 2 -> "Hindi / Marathi"
            bengaliCount > 2 -> "Bengali"
            gurmukhiCount > 2 -> "Punjabi"
            gujaratiCount > 2 -> "Gujarati"
            tamilCount > 2 -> "Tamil"
            teluguCount > 2 -> "Telugu"
            kannadaCount > 2 -> "Kannada"
            malayalamCount > 2 -> "Malayalam"
            arabicCount > 2 -> "Urdu"
            isHinglish -> "Hinglish"
            else -> "English"
        }
    }

    fun cleanUp() {
        stopSession()
        audioTrack?.release()
        audioTrack = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }
}
