package ru.secondbrain.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import ru.secondbrain.app.notifications.AgentNotifier
import ru.secondbrain.app.ui.AppRoot

/** FragmentActivity (not plain ComponentActivity) because BiometricPrompt needs it. */
class MainActivity : FragmentActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute.value = intent?.getStringExtra(AgentNotifier.EXTRA_ROUTE)
        val container = (application as SecondBrainApp).container
        setContent { AppRoot(container, this, pendingRoute) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(AgentNotifier.EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }
}
