package dev.localstream

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.localstream.client.impl.DefaultViewerSession
import dev.localstream.client.ui.VideoPane
import dev.localstream.client.ui.ViewerScreen
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val host = (app as StreamApplication).host
    val viewer = DefaultViewerSession(app)
    var mode by mutableStateOf("home")
    var backend by mutableStateOf("camera2")
    fun back() { viewModelScope.launch { viewer.disconnect(); viewer.stopDiscovery(); mode = "home" } }
    fun visible(value: Boolean) { viewModelScope.launch { viewer.setVisible(value) } }
    override fun onCleared() { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { viewer.close() } }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: MainViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[MainViewModel::class.java]
        setContent { StreamApp(model) }
    }
    override fun onStart() { super.onStart(); if (::model.isInitialized) model.visible(true) }
    override fun onStop() { if (!isChangingConfigurations) model.visible(false); super.onStop() }
}

@Composable
private fun StreamApp(model: MainViewModel) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionMessage by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val required = result.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        if (required.values.all { it }) pending?.invoke()
        else permissionMessage = "Разрешите доступ к локальной сети и, для трансляции, к камере."
        pending = null
    }
    fun authorized(camera: Boolean, action: () -> Unit) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
            if (camera) { add(Manifest.permission.CAMERA); add(Manifest.permission.POST_NOTIFICATIONS) }
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) action() else { pending = action; launcher.launch(permissions.toTypedArray()) }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF67E8C1), background = Color(0xFF101827), surface = Color(0xFF172235))) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("LOCAL CAMERA", style = MaterialTheme.typography.titleMedium)
                    if (model.mode != "home") TextButton(onClick = model::back) { Text("К выбору режима") }
                }
                if (permissionMessage.isNotEmpty()) Text(permissionMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                when (model.mode) {
                    "client" -> ViewerScreen(model.viewer)
                    "server" -> HostScreen(model) { authorized(true) {
                        permissionMessage = ""
                        context.startForegroundService(Intent(context, StreamingService::class.java).putExtra("backend", model.backend))
                    } }
                    else -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Камера рядом", style = MaterialTheme.typography.headlineLarge)
                        Text("Передавайте видео с Android-камеры на другие устройства в общей Wi-Fi сети.")
                        Button(onClick = { model.mode = "server" }, modifier = Modifier.fillMaxWidth()) { Text("Транслировать камеру") }
                        OutlinedButton(onClick = { authorized(false) {
                            permissionMessage = ""; model.mode = "client"; model.viewer.startDiscovery()
                        } }, modifier = Modifier.fillMaxWidth()) { Text("Смотреть трансляцию") }
                        Text("Android 13+ · до 5 зрителей · без интернет-сервера", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun HostScreen(model: MainViewModel, start: () -> Unit) {
    val context = LocalContext.current
    val state by model.host.state.collectAsState()
    val preview by model.host.preview.collectAsState()
    val addresses = remember(state.running) { runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }.mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList()) }
    Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Сервер камеры", style = MaterialTheme.typography.headlineSmall)
        VideoPane(preview, Modifier.fillMaxWidth())
        Text(state.phase, color = MaterialTheme.colorScheme.primary)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row {
            FilterChip(selected = model.backend == "camera2", onClick = { model.backend = "camera2" }, label = { Text("Camera2") }, enabled = !state.running && !state.starting)
            Spacer(Modifier.width(12.dp))
            FilterChip(selected = model.backend == "ndk", onClick = { model.backend = "ndk" }, label = { Text("Camera NDK") }, enabled = !state.running && !state.starting)
        }
        if (state.running) {
            Text("Код доступа: ${state.accessCode}", style = MaterialTheme.typography.headlineSmall)
            SelectionContainer { Text("SHA-256 сертификата: ${state.fingerprint}") }
            Text("Зрители: ${state.viewers} / 5")
            addresses.forEach { Text("$it:${state.port}") }
            Button(onClick = { context.startService(Intent(context, StreamingService::class.java).setAction("stop")) }) { Text("Остановить трансляцию") }
        } else Button(onClick = start, enabled = !state.starting) { Text(if (state.starting) "Запуск…" else "Начать трансляцию") }
        Text("Для просмотра подключите устройства к одной Wi-Fi сети и введите код доступа сервера.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
    }
}
