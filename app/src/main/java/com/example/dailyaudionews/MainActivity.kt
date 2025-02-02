package com.example.dailyaudionews

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.request.ImageRequest
import com.example.dailyaudionews.ui.theme.DigitalDailyPulseTheme
import com.example.digitaldailypulse.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

enum class UpdateStatus {
    CHECKING,
    DOWNLOADING,
    DOWNLOADED,
    NO_UPDATE,
    ERROR
}

class MainActivity : ComponentActivity() {

    private val CHANNEL_ID = "media_playback_channel"
    private val NOTIFICATION_ID = 1

    lateinit var exoPlayer: ExoPlayer

    // Local filenames
    private val AUDIO_FILE_NAME = "DigitalDailyPulse.mp3"
    private val META_FILE_NAME = "DigitalDailyPulse.meta"

    // Google Drive links
    private val META_DRIVE_URL =
        "https://docs.google.com/uc?export=download&id=1-HEogHoX5A1ikijnNE2uHKyUVw4s2u1K"
    private val DRIVE_URL =
        "https://docs.google.com/uc?export=download&id=1oKDx5BHyrEGnganYvCA4mSfmBXeHJxNr"

    private lateinit var notificationBuilder: NotificationCompat.Builder

    // Flows
    private val _updateStatus = MutableStateFlow<UpdateStatus?>(null)
    private val updateStatus: StateFlow<UpdateStatus?> = _updateStatus.asStateFlow()

    // Holds the meta file content (e.g. "28032023")
    private val _metaDateString = MutableStateFlow("")
    private val metaDateString: StateFlow<String> = _metaDateString.asStateFlow()

    // Stores the server’s last-modified timestamp for the meta file
    private val _metaLastModified = MutableStateFlow<Long?>(null)
    private val metaLastModified: StateFlow<Long?> = _metaLastModified.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        // Initialize notification builder
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // Use logo.png for the notification icon
        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo) // <-- Replaced old 'logo2' with 'logo'
            .setContentTitle("Digital Daily Pulse")
            .setContentText("Playing...")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // Initialize ExoPlayer for background playback
        exoPlayer = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Load existing local meta if present
        val metaFile = File(applicationContext.filesDir, META_FILE_NAME)
        if (metaFile.exists()) {
            _metaDateString.value = metaFile.readText().trim()
        }

        // Set the UI
        setContent {
            DigitalDailyPulseTheme {
                DigitalDailyPulseApp(
                    onUpdateClick = { checkForUpdate() },
                    updateStatus = updateStatus,
                    metaDateFlow = metaDateString,
                    metaLastModified = metaLastModified
                )
            }
        }

        // Begin checking for an update
        checkForUpdate()
    }

    private fun checkForUpdate() {
        lifecycleScope.launch(Dispatchers.IO) {
            _updateStatus.value = UpdateStatus.CHECKING
            delay(1000)

            try {
                val metaFile = File(applicationContext.filesDir, META_FILE_NAME)
                val audioFile = File(applicationContext.filesDir, AUDIO_FILE_NAME)
                val oldMeta: String? =
                    if (metaFile.exists()) metaFile.readText().trim() else null

                // Download meta file and get server's Last-Modified
                val (metaDownloaded, serverLastModified) = FileDownloader.downloadMetaFile(
                    context = applicationContext,
                    metaFileUrl = META_DRIVE_URL,
                    metaFileName = META_FILE_NAME
                )

                if (!metaDownloaded) {
                    // If meta download fails...
                    if (audioFile.exists()) {
                        // Use existing local news if available
                        _updateStatus.value = UpdateStatus.NO_UPDATE
                        withContext(Dispatchers.Main) {
                            preparePlayer()
                        }
                    } else {
                        _updateStatus.value = UpdateStatus.ERROR
                    }
                    delay(3000)
                    _updateStatus.value = null
                    return@launch
                }

                // Successfully downloaded meta
                val newMetaContent = metaFile.readText().trim()
                _metaDateString.value = newMetaContent
                _metaLastModified.value = serverLastModified

                // Compare old vs new meta
                if (oldMeta == null || oldMeta != newMetaContent) {
                    _updateStatus.value = UpdateStatus.DOWNLOADING
                    val mp3Success = FileDownloader.downloadMainFile(
                        context = applicationContext,
                        fileUrl = DRIVE_URL,
                        destinationFileName = AUDIO_FILE_NAME
                    )
                    if (mp3Success) {
                        _updateStatus.value = UpdateStatus.DOWNLOADED
                        withContext(Dispatchers.Main) {
                            preparePlayer()
                        }
                    } else {
                        _updateStatus.value = UpdateStatus.ERROR
                    }
                } else {
                    // No update needed; play the local file
                    _updateStatus.value = UpdateStatus.NO_UPDATE
                    withContext(Dispatchers.Main) {
                        preparePlayer()
                    }
                }

                delay(3000)
                _updateStatus.value = null

            } catch (e: Exception) {
                // If any exception, try existing local file
                val audioFile = File(applicationContext.filesDir, AUDIO_FILE_NAME)
                if (audioFile.exists()) {
                    _updateStatus.value = UpdateStatus.NO_UPDATE
                    withContext(Dispatchers.Main) {
                        preparePlayer()
                    }
                } else {
                    _updateStatus.value = UpdateStatus.ERROR
                }
                delay(3000)
                _updateStatus.value = null
            }
        }
    }

    private fun preparePlayer() {
        val localFile = File(applicationContext.filesDir, AUDIO_FILE_NAME)
        if (localFile.exists()) {
            val mediaItem = MediaItem.fromUri(localFile.toURI().toString())
            exoPlayer.setMediaItem(mediaItem)
            // Disable auto-play
            exoPlayer.playWhenReady = false
            exoPlayer.prepare()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hidePlaybackNotification()
        exoPlayer.release()
    }

    private fun createNotificationChannel() {
        val name = "Media Playback"
        val descriptionText = "Shows current playback status"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun showPlaybackNotification() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    fun hidePlaybackNotification() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}

@SuppressLint("ContextCastToActivity")
@Composable
fun DigitalDailyPulseApp(
    onUpdateClick: () -> Unit,
    updateStatus: StateFlow<UpdateStatus?>,
    metaDateFlow: StateFlow<String>,
    metaLastModified: StateFlow<Long?>
) {
    val activity = LocalContext.current as MainActivity
    val exoPlayer = activity.exoPlayer

    val status by updateStatus.collectAsState()
    val rawMetaDate by metaDateFlow.collectAsState()
    val prettyDate = remember(rawMetaDate) { formatMetaDate(rawMetaDate) }

    val metaLastModifiedTime by metaLastModified.collectAsState()

    // Countdown for next news update
    var nextNewsCountdown by remember { mutableStateOf("") }
    LaunchedEffect(rawMetaDate, metaLastModifiedTime) {
        while (true) {
            val nextNewsTimestamp = getNextNewsTimestamp(rawMetaDate, metaLastModifiedTime)
            if (nextNewsTimestamp != null) {
                val diff = nextNewsTimestamp - System.currentTimeMillis()
                nextNewsCountdown = if (diff > 0) {
                    val hours = diff / (3600 * 1000)
                    val minutes = (diff % (3600 * 1000)) / (60 * 1000)
                    "Next news in: ${hours}h ${minutes}m"
                } else {
                    "New news available!"
                }
            } else {
                nextNewsCountdown = ""
            }
            delay(60_000L)
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    // Update playback states frequently
    LaunchedEffect(exoPlayer) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Show/hide playback notification
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            activity.showPlaybackNotification()
        } else {
            activity.hidePlaybackNotification()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Top row with only the Close (X) button now
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End, // move the X button to the end
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { activity.finish() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Text(
                        "X",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            // Main logo (PNG)
            Spacer(modifier = Modifier.height(16.dp))
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "Logo",
                modifier = Modifier.size(200.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Show the current version based on the meta file
            if (prettyDate.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Current version: $prettyDate",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Display countdown
            if (nextNewsCountdown.isNotEmpty()) {
                Text(
                    text = nextNewsCountdown,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Playback controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // PLAY/PAUSE button: black with white outline
                Button(
                    onClick = {
                        if (isPlaying) {
                            exoPlayer.pause()
                        } else {
                            exoPlayer.play()
                        }
                    },
                    modifier = Modifier.width(90.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black,
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                ) {
                    Text(if (isPlaying) "Pause" else "Play")
                }

                Spacer(modifier = Modifier.width(16.dp))

                // STOP button: black with white outline
                Button(
                    onClick = {
                        exoPlayer.stop()
                        exoPlayer.seekTo(0L)
                        exoPlayer.playWhenReady = false
                        exoPlayer.prepare()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black,
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                ) {
                    Text("Stop")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Progress slider
            val positionFloat = currentPosition.toFloat()
            val durationFloat = duration.toFloat().takeIf { it > 0 } ?: 1f

            Slider(
                value = positionFloat,
                onValueChange = { newValue ->
                    exoPlayer.seekTo(newValue.toLong())
                },
                valueRange = 0f..durationFloat,
                modifier = Modifier.fillMaxWidth(0.8f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${formatDuration(currentPosition)} / ${formatDuration(duration)}",
                color = Color.White
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // CHECK FOR NEWS button: black with white outline
                Button(
                    onClick = onUpdateClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black,
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                ) {
                    Text("Check for News")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status text
            Text(
                text = when (status) {
                    UpdateStatus.CHECKING -> "Checking for News..."
                    UpdateStatus.DOWNLOADING -> "New update found, downloading..."
                    UpdateStatus.DOWNLOADED -> "Today's News downloaded successfully!"
                    UpdateStatus.NO_UPDATE -> "You have the latest News version"
                    UpdateStatus.ERROR -> "Error updating. Please try again."
                    null -> ""
                },
                color = when (status) {
                    UpdateStatus.ERROR -> Color.Red
                    UpdateStatus.DOWNLOADED -> Color.Green
                    else -> Color.White
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Version text
            Text(
                text = "ver 0.3",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Support button with the GIF, now centered below version text
            val context = LocalContext.current
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clickable {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://ko-fi.com/roundy")
                        )
                        context.startActivity(intent)
                    }
            ) {
                Image(
                    painter = rememberAsyncImagePainter(
                        ImageRequest.Builder(context)
                            .data(R.drawable.supportme)
                            .decoderFactory(GifDecoder.Factory())
                            .build()
                    ),
                    contentDescription = "Support Me",
                    modifier = Modifier
                        .fillMaxHeight()
                        .wrapContentWidth()
                        .clip(RoundedCornerShape(20.dp))
                )
            }
        }
    }
}

// ---------------------------------------------------
// Helpers below
// ---------------------------------------------------

fun formatMetaDate(raw: String): String {
    if (raw.length != 8) return raw
    val dayStr = raw.substring(0, 2)
    val monthStr = raw.substring(2, 4)
    val yearStr = raw.substring(4, 8)

    val dayNum = dayStr.toIntOrNull() ?: return raw
    val monthNum = monthStr.toIntOrNull() ?: return raw
    val yearNum = yearStr.toIntOrNull() ?: return raw

    val dayWithSuffix = dayWithSuffix(dayNum)
    val monthName = monthName(monthNum) ?: return raw

    return "$dayWithSuffix $monthName $yearNum"
}

fun dayWithSuffix(day: Int): String {
    // Special case for 11th, 12th, 13th
    if (day in 11..13) {
        return "${day}th"
    }
    return when (day % 10) {
        1 -> "${day}st"
        2 -> "${day}nd"
        3 -> "${day}rd"
        else -> "${day}th"
    }
}

fun monthName(m: Int): String? {
    return when (m) {
        1 -> "January"
        2 -> "February"
        3 -> "March"
        4 -> "April"
        5 -> "May"
        6 -> "June"
        7 -> "July"
        8 -> "August"
        9 -> "September"
        10 -> "October"
        11 -> "November"
        12 -> "December"
        else -> null
    }
}

/**
 * Formats a duration (milliseconds) into mm:ss
 */
@Composable
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Uses the meta date (DDMMYYYY) and metaLastModified (server time) to compute the next news timestamp.
 * Returns the epoch millis of 24 hours after last modified time on that date, or null if invalid.
 */
fun getNextNewsTimestamp(rawMeta: String, metaLastModified: Long?): Long? {
    if (rawMeta.length != 8) return null
    val day = rawMeta.substring(0, 2).toIntOrNull() ?: return null
    val month = rawMeta.substring(2, 4).toIntOrNull() ?: return null
    val year = rawMeta.substring(4, 8).toIntOrNull() ?: return null

    return try {
        val zone = ZoneId.systemDefault()
        // Use the server's last-modified to extract the hour/minute
        val (hour, minute) = if (metaLastModified != null) {
            val metaCreationDateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(metaLastModified),
                zone
            )
            Pair(metaCreationDateTime.hour, metaCreationDateTime.minute)
        } else {
            Pair(0, 0)
        }
        // Build a LocalDateTime from meta date + hour/minute
        val metaDateTime = LocalDateTime.of(year, month, day, hour, minute)
        // Next news is 24 hours after
        val nextNewsDateTime = metaDateTime.plusHours(24)
        nextNewsDateTime.atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
