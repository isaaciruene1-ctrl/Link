package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.core.configureAppCheck
import com.example.ui.LinkRootApp

class MainActivity : ComponentActivity() {

    private var sharedIncomingText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check with launch intent BEFORE UI or ViewModels
        configureAppCheck(this, intent)
        extractSharedText(intent)
        enableEdgeToEdge()

        setContent {
            LinkRootApp(sharedIncomingText = sharedIncomingText)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedText(intent)
    }

    private fun extractSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedIncomingText = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}
