package dev.kamiya.hermesmobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kamiya.hermesmobile.ui.AppViewModel
import dev.kamiya.hermesmobile.ui.HermesApp
import dev.kamiya.hermesmobile.ui.theme.HermesColors
import dev.kamiya.hermesmobile.ui.theme.HermesTheme
import dev.kamiya.hermesmobile.ui.theme.isHermesDark

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleConnectLink(intent)
        setContent {
            val mode by appViewModel.themeMode.collectAsStateWithLifecycle()
            val dark = isHermesDark(mode)
            // Status/navigation bar icons flip with the theme so they stay readable.
            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            HermesTheme(dark) { Surface(color = HermesColors.Ground) { HermesApp(appViewModel) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleConnectLink(intent)
    }

    /** `hermesmobile://connect?url=https://<host>.ts.net` fills in the server and signs in. */
    private fun handleConnectLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "hermesmobile" || data.host != "connect") return
        data.getQueryParameter("url")?.let(appViewModel::login)
    }
}
