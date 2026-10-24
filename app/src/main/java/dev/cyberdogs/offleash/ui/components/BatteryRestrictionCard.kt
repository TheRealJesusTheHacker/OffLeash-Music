package dev.cyberdogs.offleash.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.cyberdogs.offleash.playback.shouldShowBatteryCard

private const val PREFS_FILE = "offleash_prefs"
private const val KEY_BATTERY_CARD_DISMISSED = "battery_card_dismissed"

private fun readIsRestricted(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(PowerManager::class.java)
        !(pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false)
    } catch (e: Exception) {
        false
    }
}

/**
 * Card shown while Android battery optimization can stop background play.
 * Opens the system dialog asking to exempt OffLeash; dismissal is
 * persisted forever for this install (no nagging). Restriction state is
 * re-evaluated on resume so granting the setting removes the card at once.
 */
@Composable
fun BatteryRestrictionCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }
    var isRestricted by remember { mutableStateOf(readIsRestricted(context)) }
    var dismissed by remember {
        mutableStateOf(prefs.getBoolean(KEY_BATTERY_CARD_DISMISSED, false))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isRestricted = readIsRestricted(context)
                dismissed = prefs.getBoolean(KEY_BATTERY_CARD_DISMISSED, false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!shouldShowBatteryCard(isRestricted, dismissed)) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Allow background play",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Android may stop background play for OffLeash. " +
                    "Tap to allow unrestricted background use.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    try {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        ).setData(Uri.parse("package:" + context.packageName))
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        } catch (ignored: ActivityNotFoundException) {
                            // No settings surface on this device; the card
                            // stays until dismissed.
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Allow unrestricted background use")
            }
            TextButton(
                onClick = {
                    prefs.edit()
                        .putBoolean(KEY_BATTERY_CARD_DISMISSED, true)
                        .apply()
                    dismissed = true
                },
            ) {
                Text("Not now")
            }
        }
    }
}
