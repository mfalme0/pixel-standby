package com.pixelstandby.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import com.pixelstandby.data.SettingsRepository
import com.pixelstandby.data.WatchlistRepository
import com.pixelstandby.ui.SettingsScreen
import com.pixelstandby.ui.StandbyTheme

@OptIn(ExperimentalMaterial3Api::class)
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StandbyTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = "Pixel Standby Settings") },
                        )
                    },
                ) {
                    val settings = SettingsRepository(this@SettingsActivity)
                    val watchlist = WatchlistRepository(this@SettingsActivity)
                    SettingsScreen(settings = settings, watchlist = watchlist, modifier = Modifier.padding(it).fillMaxSize())
                }
            }
        }
    }

    companion object {
        fun start(context: android.content.Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}