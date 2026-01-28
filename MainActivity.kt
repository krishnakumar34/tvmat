package com.example.tvmat

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
// --- MOBILE COMPONENTS ---
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
// --- TV COMPONENTS (Wildcard) ---
import androidx.tv.material3.* import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. INITIALIZE MPV PLAYER (Fixes Crash)
        MPVLib.create(this)
        
        setContent {
            TiviUi()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MPVLib.destroy()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TiviUi(viewModel: PlaylistViewModel = viewModel()) {
    // CONTEXT & PREFS
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }

    // DATA STATE
    val channels by viewModel.filteredChannels.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    // UI STATE
    var currentUrl by remember {
        mutableStateOf(prefs.getPlaylistUrl() ?: "")
    }

    // CONTROLS STATE
    var videoControlsEnabled by remember { mutableStateOf(prefs.getControlsEnabled()) }

    var playingUrl by remember { mutableStateOf("") }
    var isMenuVisible by remember { mutableStateOf(true) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSearchInput by remember { mutableStateOf(false) }

    // SEEKBAR / PLAYBACK STATE
    var currentTime by remember { mutableFloatStateOf(0f) }
    var totalDuration by remember { mutableFloatStateOf(1f) } 
    var showSeekbar by remember { mutableStateOf(false) }

    // GRID STATE
    val gridState = rememberTvLazyGridState()

    // OVERLAY STATE
    var lastPlayedChannel by remember { mutableStateOf<Channel?>(null) }
    var showInfoOverlay by remember { mutableStateOf(false) }
    var numberBuffer by remember { mutableStateOf("") }

    // FOCUS MANAGEMENT
    val videoFocusRequester = remember { FocusRequester() }
    val saveButtonFocus = remember { FocusRequester() }

    // GROUPS LOGIC
    val groups = remember(channels) { channels.groupBy { it.group } }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var hasAutoPlayed by remember { mutableStateOf(false) }

    // FILE PICKER
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            currentUrl = it.toString()
            prefs.savePlaylistUrl(currentUrl)
            viewModel.loadPlaylist(currentUrl, context.contentResolver)
            showSettingsDialog = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadPlaylist(currentUrl, context.contentResolver)
    }

        // --- PLAYBACK POLLING LOOP (SAFE) ---
    LaunchedEffect(playingUrl) {
        while (true) {
            if (playingUrl.isNotEmpty()) {
                try {
                    // Wrap native calls to prevent crashes
                    val time = MPVLib.getPropertyDouble("time-pos")
                    val dur = MPVLib.getPropertyDouble("duration")
                    if (time != null) currentTime = time.toFloat()
                    if (dur != null && dur > 0) totalDuration = dur.toFloat()
                } catch (e: Exception) {
                    Log.e("MPV", "Error polling time: ${e.message}")
                }
            }
            delay(500)
        }
    }
    
    // Auto-hide seekbar
    LaunchedEffect(showSeekbar) {
        if (showSeekbar) {
            delay(3000)
            showSeekbar = false
        }
    }

    fun playChannel(channel: Channel) {
        playingUrl = channel.url
        lastPlayedChannel = channel
        isMenuVisible = false
        showInfoOverlay = true
        prefs.saveLastChannelId(channel.id)
    }

    fun zapNext() {
        if (channels.isEmpty()) return
        val currentIndex = channels.indexOfFirst { it.url == playingUrl }
        val nextIndex = if (currentIndex >= channels.size - 1) 0 else currentIndex + 1
        playChannel(channels[nextIndex])
    }

    fun zapPrevious() {
        if (channels.isEmpty()) return
        val currentIndex = channels.indexOfFirst { it.url == playingUrl }
        val prevIndex = if (currentIndex <= 0) channels.size - 1 else currentIndex - 1
        playChannel(channels[prevIndex])
    }

    // AUTO-SCROLL TO PLAYING CHANNEL
    LaunchedEffect(isMenuVisible) {
        if (isMenuVisible && playingUrl.isNotEmpty()) {
            val channel = channels.find { it.url == playingUrl }

            if (channel != null) {
                if (searchQuery.isEmpty()) {
                    selectedGroup = channel.group
                }
                delay(100)
                val currentList = if (searchQuery.isNotEmpty()) {
                    channels.filter { it.name.contains(searchQuery, true) }
                } else {
                    groups[channel.group] ?: emptyList()
                }

                val index = currentList.indexOfFirst { it.url == playingUrl }
                if (index >= 0) {
                    gridState.scrollToItem(index)
                }
            }
        } else if (!isMenuVisible) {
            videoFocusRequester.requestFocus()
        }
    }

    // STARTUP LOGIC
    LaunchedEffect(groups) {
        if (groups.isNotEmpty()) {
            if (selectedGroup == null) {
                val tamilKey = groups.keys.find { it.trim().equals("Tamil", ignoreCase = true) }
                selectedGroup = tamilKey ?: groups.keys.first()
            }

            if (!hasAutoPlayed) {
                val savedId = prefs.getLastChannelId()
                var targetChannel: Channel? = null
                if (savedId != null) {
                    targetChannel = channels.find { it.id == savedId }
                }
                if (targetChannel == null) {
                    targetChannel = channels.find { it.id.trim() == "527" }
                }
                if (targetChannel != null) {
                    playChannel(targetChannel)
                    hasAutoPlayed = true
                }
            }
        }
    }

    // AUTO HIDE INFO OVERLAY
    LaunchedEffect(showInfoOverlay, showSeekbar) {
        if (showInfoOverlay && !showSeekbar) {
            delay(4000)
            showInfoOverlay = false
        }
    }

    LaunchedEffect(numberBuffer) {
        if (numberBuffer.isNotEmpty()) {
            delay(2000)
            val index = numberBuffer.toIntOrNull()
            if (index != null && index > 0 && index <= channels.size) {
                val target = channels.getOrNull(index - 1)
                if (target != null) playChannel(target)
            }
            numberBuffer = ""
        }
    }

    LaunchedEffect(isMenuVisible) {
        if (!isMenuVisible) {
            videoFocusRequester.requestFocus()
        }
    }

    BackHandler(enabled = !isMenuVisible) {
        isMenuVisible = true
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                numberBuffer += (event.nativeKeyEvent.keyCode - KeyEvent.KEYCODE_0).toString()
                return@onPreviewKeyEvent true
            }
            false
        }
    ) {
        // LAYER 1: VIDEO PLAYER
        if (playingUrl.isNotEmpty()) {
            VideoPlayer(
                url = playingUrl,
                showControls = videoControlsEnabled,
                modifier = Modifier.fillMaxSize()
            )
        }

        // HIDDEN FOCUS TARGET (Remote Logic)
        if (!isMenuVisible) {
            Box(
                modifier = Modifier.fillMaxSize().focusRequester(videoFocusRequester).focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val keyCode = event.nativeKeyEvent.keyCode

                            if (videoControlsEnabled) {
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                        try {
                                            MPVLib.command("seek", "10")
                                            showSeekbar = true
                                        } catch (e: Exception) {}
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                        try {
                                            MPVLib.command("seek", "-10")
                                            showSeekbar = true
                                        } catch (e: Exception) {}
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                        try {
                                            MPVLib.command("cycle", "pause")
                                            showSeekbar = true
                                        } catch (e: Exception) {}
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                                        zapNext()
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                                        zapPrevious()
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_MENU -> {
                                        isMenuVisible = true
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            } else {
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                                        zapNext()
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                                        zapPrevious()
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                        isMenuVisible = true
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                        }
                        false
                    }
            )
        }

             // LAYER 2: TIVIMATE STYLE PLAYER BAR
        AnimatedVisibility(
            visible = showSeekbar || (showInfoOverlay && !isMenuVisible && lastPlayedChannel != null),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Gradient background for readability
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f))
                        )
                    )
                    .padding(start = 40.dp, end = 40.dp, bottom = 40.dp, top = 20.dp)
            ) {
                // ROW 1: Channel Info (ALWAYS VISIBLE)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF2962FF), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = lastPlayedChannel?.id ?: "CH",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = lastPlayedChannel?.name ?: "Unknown Channel",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // ROW 2: Progress Bar & Times (ONLY IF CONTROLS ENABLED)
                if (videoControlsEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatTime(currentTime),
                            color = Color(0xFFB0B0B0),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(50.dp)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF444444))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(
                                        fraction = if (totalDuration > 0) (currentTime / totalDuration).coerceIn(0f, 1f) else 0f
                                    )
                                    .background(Color(0xFF2962FF))
                            )
                        }
                        Text(
                            text = formatTime(totalDuration),
                            color = Color(0xFFB0B0B0),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }

        // LAYER 3: NUMBER BUFFER
        if (numberBuffer.isNotEmpty()) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(40.dp)
                    .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.Blue, RoundedCornerShape(8.dp)).padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("CH $numberBuffer", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }

        // LAYER 4: MAIN MENU
        AnimatedVisibility(visible = isMenuVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF151520).copy(alpha = 0.95f))) {

                // TOP BAR
                Row(
                    modifier = Modifier.fillMaxWidth().height(70.dp).background(Color(0xFF1E1E2C)).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("MY TV PLAYER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.weight(1f))

                    if (showSearchInput) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search...") },
                            modifier = Modifier.width(200.dp).height(50.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Black, unfocusedContainerColor = Color.Black,
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White
                            ),
                            singleLine = true
                        )
                    }

                    Button(
                        onClick = { showSearchInput = !showSearchInput },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Blue),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White) }

                    Button(
                        onClick = { showSettingsDialog = true },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Blue),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White) }
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    TvLazyColumn(modifier = Modifier.width(150.dp).fillMaxHeight().background(Color(0xFF1E1E2C))) {
                        items(groups.keys.toList()) { group ->
                            val isSelected = group == selectedGroup
                            Button(
                                onClick = { selectedGroup = group },
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                colors = ButtonDefaults.colors(
                                    containerColor = if (isSelected) Color.Blue else Color.Transparent,
                                    contentColor = if (isSelected) Color.White else Color.Gray,
                                    focusedContainerColor = Color.White, focusedContentColor = Color.Black
                                ),
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp))
                            ) { Text(text = group, maxLines = 1) }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(10.dp)) {
                        val displayChannels = if (searchQuery.isNotEmpty()) channels else (groups[selectedGroup] ?: emptyList())

                        if (displayChannels.isEmpty()) {
                            Text("No channels found", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                        } else {
                            TvLazyVerticalGrid(
                                state = gridState,
                                columns = TvGridCells.Fixed(1),
                                contentPadding = PaddingValues(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(displayChannels) { channel ->
                                    val isPlaying = channel.url == playingUrl
                                    Button(
                                        onClick = { playChannel(channel) },
                                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                        colors = ButtonDefaults.colors(
                                            containerColor = if (isPlaying) Color(0xFF1E88E5) else Color(0xFF2B2B38),
                                            focusedContainerColor = Color.Blue
                                        ),
                                        modifier = Modifier.height(60.dp).fillMaxWidth()
                                    ) { Text("${channel.id}. ${channel.name}", maxLines = 1, color = Color.White, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Blue)
        }

        if (showSettingsDialog) {
            var tempUrl by remember { mutableStateOf(currentUrl) }

            Dialog(onDismissRequest = { showSettingsDialog = false }) {
                Column(
                    modifier = Modifier.width(450.dp).background(Color(0xFF1E1E2C), RoundedCornerShape(12.dp)).padding(24.dp)
                ) {
                    Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.colors(containerColor = Color(0xFF2B2B38)),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp)); Text("Select Local M3U File", color = Color.White)
                    }

                    Button(
                        onClick = {
                            videoControlsEnabled = !videoControlsEnabled
                            prefs.saveControlsEnabled(videoControlsEnabled)
                        },
                        colors = ButtonDefaults.colors(containerColor = if (videoControlsEnabled) Color.Blue else Color(0xFF2B2B38)),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Icon(Icons.Default.Tv, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp)); Text(text = "Video Controls: ${if (videoControlsEnabled) "ON" else "OFF"}", color = Color.White)
                    }

                    Text("Or enter URL:", color = Color.Gray, fontSize = 12.sp)
                    OutlinedTextField(
                        value = tempUrl, onValueChange = { tempUrl = it },
                        label = { Text("Playlist URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { saveButtonFocus.requestFocus() }),
                        colors = TextFieldDefaults.colors(focusedTextColor = Color.Black, unfocusedTextColor = Color.Black, focusedContainerColor = Color.White, unfocusedContainerColor = Color.White)
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { showSettingsDialog = false }, colors = ButtonDefaults.colors(containerColor = Color.Red.copy(alpha = 0.7f)), shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp))) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                currentUrl = tempUrl; prefs.savePlaylistUrl(currentUrl)
                                viewModel.loadPlaylist(currentUrl); showSettingsDialog = false
                            },
                            modifier = Modifier.focusRequester(saveButtonFocus),
                            colors = ButtonDefaults.colors(containerColor = Color.Blue),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp))
                        ) { Text("Save & Reload") }
                    }
                }
            }
        }
    }
}

// Helper to format Seconds -> 00:00
fun formatTime(seconds: Float): String {
    val s = seconds.toLong()
    val m = s / 60
    val remS = s % 60
    return "%02d:%02d".format(m, remS)
}

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("tv_app_prefs", Context.MODE_PRIVATE)
    fun saveLastChannelId(id: String) { prefs.edit().putString("last_played_id", id).apply() }
    fun getLastChannelId(): String? { return prefs.getString("last_played_id", null) }
    fun savePlaylistUrl(url: String) { prefs.edit().putString("playlist_url", url).apply() }
    fun getPlaylistUrl(): String? { return prefs.getString("playlist_url", null) }
    fun saveControlsEnabled(enabled: Boolean) { prefs.edit().putBoolean("video_controls", enabled).apply() }
    fun getControlsEnabled(): Boolean { return prefs.getBoolean("video_controls", false) }
}

        
