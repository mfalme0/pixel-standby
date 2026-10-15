package com.pixelstandby.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelstandby.R
import com.pixelstandby.data.SettingsRepository
import com.pixelstandby.data.WatchlistRepository
import kotlinx.coroutines.launch

/**
 * Settings screen for Pixel Standby.
 *
 * Allows the user to configure:
 * - Dashboard on/off
 * - 12/24 hour clock
 * - Show seconds
 * - Auto-dim and dim level
 * - Media controls on/off
 * - Backend URL and bearer token
 * - ntfy server, topic, token
 * - Watchlist symbols
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    watchlist: WatchlistRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val dashboardEnabled by settings.dashboardEnabled.collectAsState(initial = true)
    val clock24Hour by settings.clock24Hour.collectAsState(initial = false)
    val showSeconds by settings.showSeconds.collectAsState(initial = false)
    val autoDim by settings.autoDimEnabled.collectAsState(initial = true)
    val dimLevel by settings.dimLevel.collectAsState(initial = "medium")
    val mediaEnabled by settings.mediaEnabled.collectAsState(initial = true)
    val backendUrl by settings.backendUrl.collectAsState(initial = "")
    val bearerToken by settings.bearerToken.collectAsState(initial = "")
    val ntfyServer by settings.ntfyServer.collectAsState(initial = "https://ntfy.sh")
    val ntfyTopic by settings.ntfyTopic.collectAsState(initial = "")
    val ntfyToken by settings.ntfyToken.collectAsState(initial = "")
    val watchlistSymbols by watchlist.watchlist.collectAsState(initial = WatchlistRepository.DEFAULT_WATCHLIST)

    Column(modifier = modifier.padding(16.dp)) {
        SectionTitle(text = "Dashboard")

        SettingRow(
            title = "Enable dashboard",
            subtitle = "Turn the bedside dashboard off without disabling the screen saver",
            checked = dashboardEnabled,
            onCheckedChange = { scope.launch { settings.setDashboardEnabled(it) } },
        )

        SettingRow(
            title = "24-hour clock",
            subtitle = "Show time in 24-hour format instead of 12-hour",
            checked = clock24Hour,
            onCheckedChange = { scope.launch { settings.setClock24Hour(it) } },
        )

        SettingRow(
            title = "Show seconds",
            subtitle = "Display seconds on the clock (uses more power)",
            checked = showSeconds,
            onCheckedChange = { scope.launch { settings.setShowSeconds(it) } },
        )

        SettingRow(
            title = "Auto-dim",
            subtitle = "Dim the clock after 30 min idle, near-black after 2 h",
            checked = autoDim,
            onCheckedChange = { scope.launch { settings.setAutoDimEnabled(it) } },
        )

        DimLevelRow(
            title = "Dim level",
            subtitle = "How aggressively the screen dims when idle",
            selected = dimLevel,
            onSelectedChange = { scope.launch { settings.setDimLevel(it) } },
        )

        Divider()

        SectionTitle(text = "Market data")

        SettingRow(
            title = "Media controls",
            subtitle = "Control Spotify, YouTube and other media sessions",
            checked = mediaEnabled,
            onCheckedChange = { scope.launch { settings.setMediaEnabled(it) } },
        )

        TextFieldRow(
            title = "Backend URL",
            subtitle = "Where the app fetches stock prices (required for prices)",
            value = backendUrl,
            onValueChange = { scope.launch { settings.setBackendUrl(it) } },
            placeholder = "https://your-backend.example.com",
        )

        TextFieldRow(
            title = "Bearer token",
            subtitle = "Authentication token for the backend",
            value = bearerToken,
            onValueChange = { scope.launch { settings.setBearerToken(it) } },
            placeholder = "token",
            password = true,
        )

        Divider()

        SectionTitle(text = "Notification bridge (ntfy)")

        TextFieldRow(
            title = "ntfy server",
            subtitle = "ntfy server URL for CLI notifications",
            value = ntfyServer,
            onValueChange = { scope.launch { settings.setNtfyServer(it) } },
            placeholder = "https://ntfy.sh",
        )

        TextFieldRow(
            title = "ntfy topic",
            subtitle = "Topic name for CLI notifications (must match notify-run config)",
            value = ntfyTopic,
            onValueChange = { scope.launch { settings.setNtfyTopic(it) } },
            placeholder = "my-topic",
        )

        TextFieldRow(
            title = "ntfy token",
            subtitle = "Bearer token for the ntfy server (if required)",
            value = ntfyToken,
            onValueChange = { scope.launch { settings.setNtfyToken(it) } },
            placeholder = "token",
            password = true,
        )

        Divider()

        SectionTitle(text = "Watchlist")

        TextFieldRow(
            title = "Stock tickers",
            subtitle = "Comma-separated, e.g. AAPL,MSFT,XAUUSD",
            value = watchlistSymbols.joinToString(","),
            onValueChange = { scope.launch { watchlist.setWatchlist(it.split(",").map { it.trim() }) } },
            placeholder = "AAPL,MSFT,XAUUSD",
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = title)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
    Divider()
}

@Composable
private fun DimLevelRow(
    title: String,
    subtitle: String,
    selected: String,
    onSelectedChange: (String) -> Unit,
) {
    val options = listOf("none", "medium", "aggressive")
    val labels = mapOf(
        "none" to "Never",
        "medium" to "Medium (30 min / 2 h)",
        "aggressive" to "Aggressive (10 min / 30 min)",
    )
    val currentIndex = options.indexOf(selected)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val nextIndex = (currentIndex + 1) % options.size
                onSelectedChange(options[nextIndex])
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = title)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text = labels[selected] ?: selected)
    }
    Divider()
}

@Composable
private fun TextFieldRow(
    title: String,
    subtitle: String?,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
) {
    var text by remember { mutableStateOf(value) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = title)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onValueChange(it) },
            placeholder = { Text(text = placeholder) },
            singleLine = true,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(0.4f),
        )
    }
    Divider()
}