package com.carcompanion.app.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.carcompanion.app.MainActivity
import com.carcompanion.app.service.CompanionService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class ShareNavigationActivity : ComponentActivity() {
    private var destinations by mutableStateOf<List<SharedMapsDestination>>(emptyList())
    private var selected by mutableStateOf<SharedMapsDestination?>(null)
    private var message by mutableStateOf("Opening shared destination…")
    private var busy by mutableStateOf(true)
    private var resolution: Job? = null
    private var shareGeneration = 0
    private var permissionGeneration = -1
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (permissionGeneration != shareGeneration) return@registerForActivityResult
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT))) prepareNavigation()
        else { busy = false; message = "Precise location and Nearby devices permission are needed. Tap Start navigation to try again." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xff9acdf1), background = Color(0xff080b0d))) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("Drive with Car Companion", style = MaterialTheme.typography.headlineMedium)
                        Text(message)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        destinations.forEach { destination ->
                            Card(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = destination }) {
                                Row(Modifier.padding(12.dp)) {
                                    RadioButton(selected == destination, onClick = { selected = destination }, enabled = !busy)
                                    Column(Modifier.padding(top = 10.dp)) {
                                        Text(destination.label)
                                        if (destination.latitude != null) Text("${destination.latitude}, ${destination.longitude}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        Text("Check the destination, then start. Car Companion calculates a new driving route from your current location. Maps’ selected route and extra stops are not transferred.",
                            style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { requestStart() }, enabled = selected != null && !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Start navigation")
                        }
                        TextButton(onClick = { finish() }) { Text("Cancel") }
                        Text("Navigation powered by Google Maps", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        handleShare(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }
    private fun handleShare(intent: Intent) {
        resolution?.cancel()
        val request = ++shareGeneration
        destinations = emptyList()
        selected = null
        busy = true
        message = "Opening shared destination…"
        val text = if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain")
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        if (text.isNullOrBlank()) { busy = false; message = "In Google Maps, open a destination and choose Share → Car Companion."; return }
        resolution = lifecycleScope.launch {
            try {
                val resolved = SharedMapsResolver(this@ShareNavigationActivity).resolve(text)
                if (request != shareGeneration) return@launch
                destinations = resolved
                selected = destinations.singleOrNull()
                message = if (destinations.size == 1) "Confirm your destination" else "Choose the matching destination"
            } catch (_: TimeoutCancellationException) { if (request == shareGeneration) message = "Destination lookup timed out. Share the destination again when connected." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (request == shareGeneration) message = failure.message?.take(240) ?: "Could not open this Maps link. Share the destination again." }
            finally { if (request == shareGeneration) busy = false }
        }
    }
    private fun requestStart() {
        busy = true
        permissionGeneration = shareGeneration
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot(::granted)
        if (required.isEmpty()) prepareNavigation() else permissions.launch(required.toTypedArray())
    }
    private fun prepareNavigation() {
        val destination = selected ?: run { busy = false; return }
        val request = shareGeneration
        message = "Starting Google navigation…"
        GoogleNavigationSession.prepare(this, ready = {
            if (request != shareGeneration) return@prepare
            CompanionService.navigate(this, destination)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }, failed = { if (request == shareGeneration) { message = it; busy = false } })
    }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
