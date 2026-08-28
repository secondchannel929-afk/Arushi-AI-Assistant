package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.assistant.ArushiUiState
import com.example.assistant.ArushiViewModel
import com.example.assistant.ChatMessage
import com.example.assistant.LiveConnectionState
import com.example.assistant.MessageRole
import com.example.ui.theme.ArushiAccentGreen
import com.example.ui.theme.ArushiAccentRed
import com.example.ui.theme.ArushiCyan
import com.example.ui.theme.ArushiDarkBackground
import com.example.ui.theme.ArushiDarkSurface
import com.example.ui.theme.ArushiDarkSurfaceVariant
import com.example.ui.theme.ArushiPink
import com.example.ui.theme.ArushiPurplePrimary
import com.example.ui.theme.ArushiTextPrimary
import com.example.ui.theme.ArushiTextSecondary
import com.example.ui.theme.MyApplicationTheme

import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff

class MainActivity : ComponentActivity() {
    private val viewModel: ArushiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                ArushiAppScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun ArushiAppScreen(viewModel: ArushiViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var textInput by remember { mutableStateOf("") }
    var showTextInputBar by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf(viewModel.getSavedApiKey()) }
    var isApiKeyVisible by remember { mutableStateOf(false) }

    // Permission launcher for Audio, Contacts, and Phone Call
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val contactsGranted = permissions[Manifest.permission.READ_CONTACTS] ?: false
        val callGranted = permissions[Manifest.permission.CALL_PHONE] ?: false
        viewModel.updatePermissions(audioGranted, contactsGranted, callGranted)
    }

    LaunchedEffect(Unit) {
        val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val call = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        viewModel.updatePermissions(audio, contacts, call)

        if (!audio || !contacts || !call) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE
                )
            )
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ArushiDarkBackground)
            .imePadding(),
        containerColor = ArushiDarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Top Header & Status Bar
            ArushiHeader(
                uiState = uiState,
                onOpenSettings = {
                    apiKeyInput = viewModel.getSavedApiKey()
                    showSettingsDialog = true
                },
                onRequestPermissions = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.CALL_PHONE
                        )
                    )
                }
            )

            // 2. Central Visualizer & Messages Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.messages.isEmpty()) {
                    ArushiHeroVisualizer(
                        connectionState = uiState.connectionState,
                        volumeLevel = uiState.audioVolumeLevel,
                        statusText = uiState.statusMessage,
                        language = uiState.detectedLanguage,
                        isApiKeyConfigured = uiState.isApiKeyConfigured,
                        onOpenSettings = {
                            apiKeyInput = viewModel.getSavedApiKey()
                            showSettingsDialog = true
                        },
                        onSamplePrompt = { prompt ->
                            if (uiState.connectionState == LiveConnectionState.DISCONNECTED) {
                                viewModel.startSession()
                            }
                            viewModel.sendTextMessage(prompt)
                        }
                    )
                } else {
                    ArushiChatAndVisualizer(
                        uiState = uiState
                    )
                }
            }

            // 3. Quick Action Chips Carousel
            QuickActionChipsRow(
                onPromptSelected = { prompt ->
                    if (uiState.connectionState == LiveConnectionState.DISCONNECTED) {
                        viewModel.startSession()
                    }
                    viewModel.sendTextMessage(prompt)
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 4. Text Input Field (if toggled)
            AnimatedVisibility(
                visible = showTextInputBar,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("text_input_field"),
                        placeholder = {
                            Text("Type question or command (Hindi / English)...", color = ArushiTextSecondary, fontSize = 13.sp)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ArushiCyan,
                            unfocusedBorderColor = ArushiDarkSurfaceVariant,
                            focusedTextColor = ArushiTextPrimary,
                            unfocusedTextColor = ArushiTextPrimary,
                            cursorColor = ArushiCyan
                        ),
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (textInput.isNotBlank()) {
                                if (uiState.connectionState == LiveConnectionState.DISCONNECTED) {
                                    viewModel.startSession()
                                }
                                viewModel.sendTextMessage(textInput)
                                textInput = ""
                            }
                        })
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                if (uiState.connectionState == LiveConnectionState.DISCONNECTED) {
                                    viewModel.startSession()
                                }
                                viewModel.sendTextMessage(textInput)
                                textInput = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(ArushiPurplePrimary, CircleShape)
                            .testTag("send_text_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.White
                        )
                    }
                }
            }

            // 5. Bottom Voice Controls Bar
            ArushiBottomControls(
                uiState = uiState,
                showTextInput = showTextInputBar,
                onToggleTextInput = { showTextInputBar = !showTextInputBar },
                onMainMicClick = {
                    if (!uiState.isApiKeyConfigured && viewModel.getSavedApiKey().isBlank()) {
                        apiKeyInput = viewModel.getSavedApiKey()
                        showSettingsDialog = true
                    } else if (uiState.connectionState == LiveConnectionState.DISCONNECTED || uiState.connectionState == LiveConnectionState.ERROR) {
                        viewModel.startSession()
                    } else {
                        viewModel.stopSession()
                    }
                },
                onToggleMute = {
                    viewModel.toggleMute()
                }
            )
        }
    }

    // API Key / Settings Dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = ArushiDarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = ArushiCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Google Gemini API Key",
                        style = MaterialTheme.typography.titleMedium,
                        color = ArushiTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Enter your Google Gemini API Key to talk with Arushi and get answers to all your questions in Hindi, English, and all Indian languages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArushiTextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("Gemini API Key", color = ArushiCyan) },
                        placeholder = { Text("AIzaSy...", color = ArushiTextSecondary) },
                        visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                Icon(
                                    imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle API Key visibility",
                                    tint = ArushiTextSecondary
                                )
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ArushiCyan,
                            unfocusedBorderColor = ArushiDarkSurfaceVariant,
                            focusedTextColor = ArushiTextPrimary,
                            unfocusedTextColor = ArushiTextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("api_key_input_field")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "💡 Tip: Get your free API key at Google AI Studio (aistudio.google.com).",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArushiCyan,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveCustomApiKey(apiKeyInput)
                        showSettingsDialog = false
                        viewModel.startSession()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ArushiPurplePrimary),
                    modifier = Modifier.testTag("save_api_key_button")
                ) {
                    Text("Save & Connect", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel", color = ArushiTextSecondary)
                }
            }
        )
    }
}

@Composable
fun ArushiHeader(
    uiState: ArushiUiState,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    Surface(
        color = ArushiDarkSurface.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    listOf(ArushiPurplePrimary, ArushiCyan)
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Arushi AI",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ArushiTextPrimary
                        )
                        Text(
                            text = "Multilingual Voice & App Control",
                            style = MaterialTheme.typography.bodySmall,
                            color = ArushiCyan,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // API Key Pill Button
                    Surface(
                        color = if (uiState.isApiKeyConfigured) ArushiAccentGreen.copy(alpha = 0.15f) else ArushiPink.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (uiState.isApiKeyConfigured) ArushiAccentGreen.copy(alpha = 0.4f) else ArushiPink.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier
                            .clickable { onOpenSettings() }
                            .testTag("api_key_header_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = if (uiState.isApiKeyConfigured) ArushiAccentGreen else ArushiPink,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (uiState.isApiKeyConfigured) "API Ready" else "Set Key",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.isApiKeyConfigured) ArushiAccentGreen else ArushiPink,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Connection State Pill
                    ConnectionStateBadge(uiState.connectionState)

                    Spacer(modifier = Modifier.width(6.dp))

                    // Settings Button
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(36.dp)
                            .background(ArushiDarkSurfaceVariant, CircleShape)
                            .testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = ArushiTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Missing permissions warning bar
            if (!uiState.hasAudioPermission || !uiState.hasContactsPermission || !uiState.hasCallPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ArushiDarkSurfaceVariant, RoundedCornerShape(12.dp))
                        .clickable { onRequestPermissions() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("permission_grant_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = ArushiPink,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Grant Mic, Contacts & Call permissions",
                            style = MaterialTheme.typography.bodySmall,
                            color = ArushiTextPrimary,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = "GRANT",
                        color = ArushiCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionStateBadge(state: LiveConnectionState) {
    val (bgColor, dotColor, label) = when (state) {
        LiveConnectionState.DISCONNECTED -> Triple(
            ArushiDarkSurfaceVariant,
            ArushiTextSecondary,
            "Idle"
        )
        LiveConnectionState.CONNECTING -> Triple(
            ArushiPurplePrimary.copy(alpha = 0.2f),
            ArushiPurplePrimary,
            "Connecting"
        )
        LiveConnectionState.CONNECTED -> Triple(
            ArushiCyan.copy(alpha = 0.2f),
            ArushiCyan,
            "Live Ready"
        )
        LiveConnectionState.LISTENING -> Triple(
            ArushiAccentGreen.copy(alpha = 0.2f),
            ArushiAccentGreen,
            "Listening"
        )
        LiveConnectionState.THINKING -> Triple(
            ArushiPurplePrimary.copy(alpha = 0.2f),
            ArushiPurplePrimary,
            "Thinking..."
        )
        LiveConnectionState.SPEAKING -> Triple(
            ArushiCyan.copy(alpha = 0.2f),
            ArushiCyan,
            "Speaking"
        )
        LiveConnectionState.ERROR -> Triple(
            ArushiAccentRed.copy(alpha = 0.2f),
            ArushiAccentRed,
            "Attention"
        )
    }

    Row(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(16.dp))
            .border(1.dp, dotColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ArushiTextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )
    }
}

@Composable
fun ArushiHeroVisualizer(
    connectionState: LiveConnectionState,
    volumeLevel: Float,
    statusText: String,
    language: String,
    isApiKeyConfigured: Boolean,
    onOpenSettings: () -> Unit,
    onSamplePrompt: (String) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val animatedVol by animateFloatAsState(
        targetValue = volumeLevel,
        animationSpec = tween(80),
        label = "vol"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // API Key prompt banner if not yet configured
        if (!isApiKeyConfigured) {
            Surface(
                color = ArushiDarkSurfaceVariant,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ArushiPink.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() }
                    .padding(bottom = 16.dp)
                    .testTag("api_key_banner_card")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = ArushiPink,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Set Gemini API Key",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = ArushiTextPrimary
                            )
                            Text(
                                text = "Tap to enter key & chat with Arushi AI",
                                style = MaterialTheme.typography.bodySmall,
                                color = ArushiTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = ArushiPurplePrimary),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Add Key", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        // Glowing animated orb
        Box(
            modifier = Modifier.size(210.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val baseRadius = size.width / 3.2f
                val dynamicRadius = baseRadius * pulseScale + (animatedVol * 38.dp.toPx())

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ArushiPurplePrimary.copy(alpha = 0.35f + animatedVol * 0.4f),
                            ArushiCyan.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = dynamicRadius * 1.5f
                    ),
                    center = center,
                    radius = dynamicRadius * 1.4f
                )

                drawCircle(
                    color = ArushiCyan.copy(alpha = 0.5f + animatedVol * 0.4f),
                    center = center,
                    radius = dynamicRadius,
                    style = Stroke(width = 2.dp.toPx())
                )

                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(ArushiPurplePrimary, ArushiCyan, ArushiPink),
                        start = Offset(center.x - baseRadius, center.y - baseRadius),
                        end = Offset(center.x + baseRadius, center.y + baseRadius)
                    ),
                    center = center,
                    radius = baseRadius * (if (connectionState == LiveConnectionState.SPEAKING) pulseScale else 1f)
                )
            }

            Icon(
                imageVector = when (connectionState) {
                    LiveConnectionState.SPEAKING -> Icons.Default.Refresh
                    LiveConnectionState.LISTENING -> Icons.Default.Mic
                    LiveConnectionState.THINKING -> Icons.Default.Refresh
                    LiveConnectionState.ERROR -> Icons.Default.Error
                    else -> Icons.Default.Mic
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(42.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = ArushiTextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Auto-Language: Hindi • English • Hinglish • Marathi & more",
            style = MaterialTheme.typography.bodySmall,
            color = ArushiCyan,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Sample Questions / Quick Prompts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Center
        ) {
            val starterPrompts = listOf(
                "🌍 Bharat ki rajdhani?",
                "💬 WhatsApp kholo",
                "🤖 What is AI?",
                "😂 Ek joke sunao"
            )
            starterPrompts.forEach { prompt ->
                Surface(
                    color = ArushiDarkSurfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ArushiCyan.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clickable { onSamplePrompt(prompt) }
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArushiTextPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ArushiChatAndVisualizer(
    uiState: ArushiUiState
) {
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size, uiState.currentAssistantSpeech) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.connectionState == LiveConnectionState.SPEAKING || uiState.connectionState == LiveConnectionState.LISTENING) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(ArushiDarkSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (uiState.connectionState == LiveConnectionState.SPEAKING) ArushiCyan else ArushiAccentGreen,
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.connectionState == LiveConnectionState.SPEAKING) "Arushi is speaking..." else "Listening...",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArushiTextPrimary
                    )
                }

                Text(
                    text = "Lang: ${uiState.detectedLanguage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArushiCyan,
                    fontSize = 11.sp
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                ChatMessageItem(message)
            }
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    when (message.role) {
        MessageRole.USER -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .background(
                            brush = Brush.linearGradient(
                                listOf(ArushiPurplePrimary, Color(0xFF7C3AED))
                            ),
                            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                        )
                        .padding(14.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        MessageRole.ASSISTANT -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .background(
                            ArushiDarkSurfaceVariant,
                            RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
                        )
                        .border(1.dp, ArushiCyan.copy(alpha = 0.3f), RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Arushi",
                                style = MaterialTheme.typography.labelMedium,
                                color = ArushiCyan,
                                fontWeight = FontWeight.Bold
                            )
                            if (!message.language.isNullOrBlank()) {
                                Text(
                                    text = message.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ArushiTextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArushiTextPrimary
                        )
                    }
                }
            }
        }

        MessageRole.ACTION -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.actionSuccess == true) Color(0xFF063528) else Color(0xFF3B1219)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (message.actionSuccess == true) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (message.actionSuccess == true) ArushiAccentGreen else ArushiAccentRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Action: ${message.actionName ?: "Device Command"}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (message.actionSuccess == true) ArushiAccentGreen else ArushiAccentRed
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = ArushiTextPrimary
                        )
                    }
                }
            }
        }

        MessageRole.SYSTEM -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArushiTextSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun QuickActionChipsRow(onPromptSelected: (String) -> Unit) {
    val quickPrompts = listOf(
        "WhatsApp kholo",
        "Open WhatsApp",
        "Mummy ko call karo",
        "Call Rahul",
        "Call 9876543210",
        "Hindi mein baat karo",
        "Talk to me in English",
        "Hinglish mein baat karo",
        "Open Instagram",
        "Open Settings",
        "Open Chrome",
        "YouTube open karo"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "TRY VOICE COMMANDS",
            style = MaterialTheme.typography.labelSmall,
            color = ArushiTextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickPrompts.forEachIndexed { index, prompt ->
                Surface(
                    color = ArushiDarkSurfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    border = border(prompt),
                    modifier = Modifier
                        .clickable { onPromptSelected(prompt) }
                        .testTag("test_query_chip_$index")
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArushiTextPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun border(prompt: String): androidx.compose.foundation.BorderStroke {
    return if (prompt.contains("WhatsApp") || prompt.contains("call", ignoreCase = true) || prompt.contains("karo")) {
        androidx.compose.foundation.BorderStroke(1.dp, ArushiCyan.copy(alpha = 0.5f))
    } else {
        androidx.compose.foundation.BorderStroke(1.dp, ArushiDarkSurfaceVariant)
    }
}

@Composable
fun ArushiBottomControls(
    uiState: ArushiUiState,
    showTextInput: Boolean,
    onToggleTextInput: () -> Unit,
    onMainMicClick: () -> Unit,
    onToggleMute: () -> Unit
) {
    val isLive = uiState.connectionState != LiveConnectionState.DISCONNECTED && uiState.connectionState != LiveConnectionState.ERROR

    Surface(
        color = ArushiDarkSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .size(50.dp)
                    .background(
                        if (uiState.isMicMuted) ArushiAccentRed.copy(alpha = 0.2f) else ArushiDarkSurfaceVariant,
                        CircleShape
                    )
                    .testTag("mute_button")
            ) {
                Icon(
                    imageVector = if (uiState.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Mute",
                    tint = if (uiState.isMicMuted) ArushiAccentRed else ArushiTextPrimary
                )
            }

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .shadow(16.dp, CircleShape, spotColor = ArushiPurplePrimary)
                    .background(
                        brush = Brush.radialGradient(
                            colors = if (isLive) listOf(ArushiAccentRed, Color(0xFFDC2626))
                            else listOf(ArushiPurplePrimary, ArushiCyan)
                        ),
                        shape = CircleShape
                    )
                    .clickable { onMainMicClick() }
                    .testTag("main_mic_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isLive) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isLive) "End Voice Session" else "Start Voice Session",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }

            IconButton(
                onClick = onToggleTextInput,
                modifier = Modifier
                    .size(50.dp)
                    .background(
                        if (showTextInput) ArushiCyan.copy(alpha = 0.2f) else ArushiDarkSurfaceVariant,
                        CircleShape
                    )
                    .testTag("keyboard_toggle_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = "Text Input",
                    tint = if (showTextInput) ArushiCyan else ArushiTextPrimary
                )
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier, color = ArushiTextPrimary)
}
