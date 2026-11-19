package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.firebase.configureAppCheck
import com.example.ui.navigation.ApplyFlowApp
import com.example.ui.theme.ApplyFlowTheme

class MainActivity : ComponentActivity() {
    private var incomingSharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Step 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)
        extractSharedText(intent)?.let { incomingSharedText = it }
        enableEdgeToEdge()
        setContent {
            ApplyFlowTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ApplyFlowApp(
                        sharedPostText = incomingSharedText,
                        onSharedTextConsumed = { incomingSharedText = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedText(intent)?.let { incomingSharedText = it }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    }
}
