package io.github.nikitapetroff.timelineexporter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.nikitapetroff.timelineexporter.ui.TimelineExporterApp
import io.github.nikitapetroff.timelineexporter.ui.TimelineViewModel
import io.github.nikitapetroff.timelineexporter.ui.theme.TimelineExporterTheme

class MainActivity : ComponentActivity() {

    // Activity-scoped ViewModel. Compose's `viewModel()` inside MainScreen
    // returns the same instance because both use this Activity's
    // ViewModelStore.
    private val viewModel: TimelineViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen MUST be called before super.onCreate. It
        // swaps the launch theme for the post-splash theme and starts
        // the splash dismiss animation when the first frame is drawn.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Handle the intent that started us (share/view), if any.
        handleIncomingIntent(intent)
        setContent {
            TimelineExporterTheme {
                TimelineExporterApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-set so getIntent() returns the latest; not strictly needed but
        // standard practice when an activity has launchMode=singleTop.
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * If [intent] is a SEND with a JSON stream attached or a VIEW pointing at
     * a content/file URI, hand it straight to the parser as if the user had
     * picked it via the in-app file picker.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            viewModel.onFileSelected(uri, contentResolver)
        }
    }
}
