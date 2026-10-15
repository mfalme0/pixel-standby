package com.pixelstandby.ntfy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pixelstandby.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * A foreground service that maintains an SSE connection to an ntfy topic.
 *
 * Received messages are posted to [NtfyBannerManager] so they can be shown as banners
 * in the dream and as standard Android notifications.
 */
class NtfySubscriberService : Service() {

    private var subscriberJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var running = false

    private val channelId = "pixel_standby_ntfy"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        startForeground(1, buildNotification("ntfy subscriber", "Connecting…"))
        val topic = intent?.getStringExtra(EXTRA_TOPIC) ?: ""
        val server = intent?.getStringExtra(EXTRA_SERVER) ?: "https://ntfy.sh"
        val token = intent?.getStringExtra(EXTRA_TOKEN)

        subscriberJob?.cancel()
        subscriberJob = scope.launch {
            while (running) {
                try {
                    subscribeSse(server, topic, token)
                } catch (e: Exception) {
                    // Swallow — the reconnect loop handles it.
                }
                if (!running) break
                // Exponential backoff, capped at 60 seconds.
                var delayMs = 1_000L
                while (running) {
                    withContext(Dispatchers.Default) {
                        kotlinx.coroutines.delay(delayMs)
                    }
                    if (!running) break
                    try {
                        subscribeSse(server, topic, token)
                        break
                    } catch (e: Exception) {
                        delayMs = (delayMs * 2).coerceAtMost(60_000)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        subscriberJob?.cancel()
        subscriberJob = null
        stopForeground(true)
        super.onDestroy()
    }

    private suspend fun subscribeSse(server: String, topic: String, token: String?) {
        val url = URL("$server/$topic/stream?json")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("Cache-Control", "no-cache")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }

        try {
            NtfyBannerManager.updateConnectionStatus(true)
            val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
            try {
                var line: String? = null
                while (running) {
                    line = reader.readLine()
                    if (line == null) break
                    if (line.isNotEmpty() && line.startsWith("data: ")) {
                        val json = line.substring(6)
                        val msg = NtfyMessage.parse(json)
                        if (msg != null) {
                            NtfyBannerManager.receive(msg)
                        }
                    }
                }
            } finally {
                reader.close()
            }
        } finally {
            connection.disconnect()
        }
        NtfyBannerManager.updateConnectionStatus(false)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Pixel Standby NTFY", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val intent = Intent(this, StubActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        const val EXTRA_TOPIC = "topic"
        const val EXTRA_SERVER = "server"
        const val EXTRA_TOKEN = "token"

        fun start(context: Context, topic: String, server: String, token: String?) {
            val intent = Intent(context, NtfySubscriberService::class.java).apply {
                putExtra(EXTRA_TOPIC, topic)
                putExtra(EXTRA_SERVER, server)
                putExtra(EXTRA_TOKEN, token)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NtfySubscriberService::class.java))
        }
    }
}

/** A parsed ntfy message. */
data class NtfyMessage(
    val title: String,
    val message: String,
    val priority: String = "default",
    val tags: List<String> = emptyList(),
    val time: Long = System.currentTimeMillis(),
) {
    companion object {
        fun parse(json: String): NtfyMessage? {
            return try {
                val title = extractString(json, "title") ?: "ntfy"
                val message = extractString(json, "message") ?: ""
                val priority = extractString(json, "priority") ?: "default"
                val tags = extractTags(json)
                val time = extractLong(json, "time") ?: System.currentTimeMillis()
                NtfyMessage(title, message, priority, tags, time)
            } catch (e: Exception) {
                null
            }
        }

        private fun extractString(json: String, key: String): String? {
            val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
            val regex = java.util.regex.Pattern.compile(pattern)
            val m = regex.matcher(json)
            return if (m.find()) m.group(1) else null
        }

        private fun extractLong(json: String, key: String): Long? {
            val pattern = "\"$key\"\\s*:\\s*(\\d+)"
            val regex = java.util.regex.Pattern.compile(pattern)
            val m = regex.matcher(json)
            return if (m.find()) m.group(1).toLong() else null
        }

        private fun extractTags(json: String): List<String> {
            val pattern = "\"tags\"\\s*:\\s*\\[([^\\]]*)\\]"
            val regex = java.util.regex.Pattern.compile(pattern)
            val m = regex.matcher(json)
            return if (!m.find()) emptyList() else {
                m.group(1).split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
            }
        }
    }
}

/**
 * Manages ntfy banners and history.
 *
 * Banners are shown for 15 seconds, then move to a history strip. The last 20 messages
 * are kept in memory. Priority controls the banner color.
 */
object NtfyBannerManager {

    private val messages = ConcurrentLinkedDeque<NtfyBanner>()
    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus: StateFlow<Boolean> = _connectionStatus.asStateFlow()

    private val _banners = MutableStateFlow<List<NtfyBanner>>(emptyList())
    val banners: StateFlow<List<NtfyBanner>> = _banners.asStateFlow()

    /** The last 20 banners, newest first. */
    val recent: List<NtfyBanner>
        get() = messages.iterator().asSequence().take(20).toList()

    fun receive(msg: NtfyMessage) {
        val banner = NtfyBanner(
            id = msg.time,
            title = msg.title,
            message = msg.message,
            priority = msg.priority,
            time = msg.time,
        )
        messages.addFirst(banner)
        while (messages.size > 20) {
            messages.removeLast()
        }
        _banners.value = recent
        postAndroidNotification(banner)
    }

    fun updateConnectionStatus(connected: Boolean) {
        _connectionStatus.value = connected
    }

    private fun postAndroidNotification(banner: NtfyBanner) {
        val ctx = PixelStandbyApp.context
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "pixel_standby_banners"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Pixel Standby Banners", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(ctx, StubActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, banner.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val color = when (banner.priority) {
            "urgent" -> 0xFFFF0000.toInt() // red
            "high" -> 0xFFFFBF00.toInt()   // amber
            "low" -> 0xFF9E9E9E.toInt()    // grey
            else -> 0xFFFFFFFF.toInt()     // white
        }
        val notification = NotificationCompat.Builder(ctx, channelId)
            .setContentTitle(banner.title)
            .setContentText(banner.message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setColor(color)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(when (banner.priority) {
                "urgent" -> NotificationCompat.PRIORITY_MAX
                "high" -> NotificationCompat.PRIORITY_HIGH
                "low" -> NotificationCompat.PRIORITY_LOW
                else -> NotificationCompat.PRIORITY_DEFAULT
            })
            .build()
        manager.notify(banner.id.toInt(), notification)
    }
}

/** A banner with timing info for the dream UI. */
data class NtfyBanner(
    val id: Long,
    val title: String,
    val message: String,
    val priority: String,
    val time: Long,
) {
    /** Milliseconds since this banner was received. */
    fun ageMillis(now: Long): Long = now - time

    /** True if the banner should still be shown as a banner (under 15 seconds old). */
    fun isCurrent(now: Long): Boolean = ageMillis(now) < 15_000

    val priorityColor: Int get() = when (priority) {
        "urgent" -> 0xFFFF0000.toInt()
        "high" -> 0xFFFFBF00.toInt()
        "low" -> 0xFF9E9E9E.toInt()
        else -> 0xFFFFFFFF.toInt()
    }
}

/** Application class to hold a static context for notifications. */
class PixelStandbyApp : android.app.Application() {
    companion object {
        lateinit var context: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }
}

/** Stub activity used as a pending-intent target for notifications. */
class StubActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}