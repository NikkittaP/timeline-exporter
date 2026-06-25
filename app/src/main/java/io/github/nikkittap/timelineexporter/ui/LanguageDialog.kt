package io.github.nikkittap.timelineexporter.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nikkittap.timelineexporter.LocaleManager
import io.github.nikkittap.timelineexporter.R

/**
 * Each language is shown by its own endonym (its name in its own script), which
 * is the convention for language pickers — users recognize their language even
 * if the rest of the UI is in another language. Tag "" = follow the system.
 */
private val LANGUAGES = listOf(
    "en" to "English",
    "ru" to "Русский",
    "uk" to "Українська",
    "de" to "Deutsch",
    "fr" to "Français",
    "es" to "Español",
    "it" to "Italiano",
    "pt-BR" to "Português (Brasil)",
    "nl" to "Nederlands",
    "sv" to "Svenska",
    "pl" to "Polski",
    "tr" to "Türkçe",
    "ja" to "日本語",
    "ko" to "한국어",
    "zh-CN" to "简体中文",
    "zh-TW" to "繁體中文",
    "hi" to "हिन्दी",
    "ar" to "العربية",
)

@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = remember { LocaleManager.getPersistedTag(context) }

    val apply: (String) -> Unit = { tag ->
        if (tag != current) {
            LocaleManager.setLanguage(context, tag)
            // Recreate so attachBaseContext re-applies the new locale.
            context.findActivity()?.recreate()
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.date_dialog_cancel))
            }
        },
        title = { Text(stringResource(R.string.language_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LanguageOption(
                    label = stringResource(R.string.language_system),
                    selected = current.isEmpty(),
                    onClick = { apply("") },
                )
                LANGUAGES.forEach { (tag, label) ->
                    LanguageOption(
                        label = label,
                        selected = current == tag,
                        onClick = { apply(tag) },
                    )
                }
            }
        },
    )
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
