package io.github.jqssun.airplay.mirror

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.ui.dpadFocus
import io.github.jqssun.airplay.ui.dpadFocusPill
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import java.util.concurrent.ConcurrentLinkedQueue

data class MirrorTarget(val name: String, val host: String, val port: Int)

class MirrorSenderActivity : ComponentActivity() {

    private var pending: MirrorTarget? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Playback capture is optional. Denying audio permission still allows screen video.
        pending?.let { launchCapture(it) }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = pending
        if (result.resultCode == RESULT_OK && result.data != null && target != null) {
            val i = Intent(this, MirrorSenderService::class.java).apply {
                putExtra(MirrorSenderService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MirrorSenderService.EXTRA_RESULT_DATA, result.data)
                putExtra(MirrorSenderService.EXTRA_HOST, target.host)
                putExtra(MirrorSenderService.EXTRA_PORT, target.port)
            }
            ContextCompat.startForegroundService(this, i)
            finish()
        }
    }

    private fun requestMirror(target: MirrorTarget) {
        pending = target
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else launchCapture(target)
    }

    private fun launchCapture(target: MirrorTarget) {
        pending = target
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AirPlayTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MirrorSenderScreen(
                        onPick = { requestMirror(it) },
                        onStop = {
                            startService(Intent(this, MirrorSenderService::class.java)
                                .setAction(MirrorSenderService.ACTION_STOP))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MirrorSenderScreen(onPick: (MirrorTarget) -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val devices = remember { mutableStateListOf<MirrorTarget>() }
    var active by remember { mutableStateOf(MirrorSenderState.active) }
    var manualHost by remember { mutableStateOf("") }
    val validHost = manualHost.trim().split('.').let { parts ->
        parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }
    }

    LaunchedEffect(Unit) {
        while (true) { active = MirrorSenderState.active; kotlinx.coroutines.delay(500) }
    }

    DisposableEffect(Unit) {
        val discovery = MirrorDiscovery(ctx) { list ->
            devices.clear(); devices.addAll(list)
        }
        discovery.start()
        onDispose { discovery.stop() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = stringResource(R.string.mirror_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.mirror_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Text(stringResource(R.string.mirror_compat_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}"))
            try { ctx.startActivity(intent) } catch (_: Exception) {}
        }) { Text(stringResource(R.string.mirror_background_settings)) }

        if (active) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        stringResource(R.string.mirror_active, MirrorSenderState.target ?: ""),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onStop, modifier = Modifier.dpadFocusPill()) {
                        Text(stringResource(R.string.mirror_stop))
                    }
                }
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = manualHost, onValueChange = { manualHost = it.take(15) },
                label = { Text(stringResource(R.string.mirror_manual_host)) }, singleLine = true,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(enabled = validHost, onClick = {
                onPick(MirrorTarget(manualHost.trim(), manualHost.trim(), MirrorProtocol.DEFAULT_PORT))
            }) { Text(stringResource(R.string.mirror_connect)) }
        }
        Spacer(Modifier.height(12.dp))

        TextButton(onClick = { ctx.startActivity(Intent(ctx, io.github.jqssun.airplay.MainActivity::class.java)) }) {
            Text(stringResource(R.string.mirror_receive_mode))
        }

        if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.mirror_searching))
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { d ->
                    ListItem(
                        modifier = Modifier.dpadFocus(RectangleShape),
                        leadingContent = { Icon(Icons.Default.Tv, null) },
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text("${d.host}:${d.port}") },
                        trailingContent = {
                            Button(onClick = { onPick(d) }, modifier = Modifier.dpadFocusPill()) {
                                Text(stringResource(R.string.mirror_connect))
                            }
                        }
                    )
                }
            }
        }
    }
}

/** NSD discovery + sequential resolve for mirror receivers on the LAN. */
class MirrorDiscovery(ctx: Context, private val onUpdate: (List<MirrorTarget>) -> Unit) {
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = LinkedHashMap<String, MirrorTarget>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private val ownAddresses = java.net.NetworkInterface.getNetworkInterfaces()?.toList()
        ?.flatMap { it.inetAddresses.toList() }?.map { it.hostAddress }?.toSet().orEmpty()

    private fun publish() {
        val snapshot = found.values.toList()
        main.post { if (active) onUpdate(snapshot) }
    }

    fun start() {
        active = true
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (active && info.serviceType.contains("airmirror")) { resolveQueue.add(info); pump() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName); publish()
            }
        }
        listener = l
        try { nsd.discoverServices(MirrorProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    @Synchronized
    private fun pump() {
        if (!active || resolving) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { resolving = false; pump() }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (active && host != null && host !in ownAddresses) {
                    found[info.serviceName] = MirrorTarget(info.serviceName, host, info.port)
                    publish()
                }
                resolving = false
                pump()
            }
        })
    }

    fun stop() {
        active = false
        resolveQueue.clear()
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
    }
}
