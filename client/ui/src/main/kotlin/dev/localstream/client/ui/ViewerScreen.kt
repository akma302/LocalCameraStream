package dev.localstream.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.localstream.client.api.*
import dev.localstream.video.api.VideoOutput
import kotlinx.coroutines.launch

@Composable
fun VideoPane(output: VideoOutput?, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (output != null) key(output) {
            AndroidView(factory = output::createView, modifier = Modifier.fillMaxSize(), onRelease = output::releaseView)
        } else Text("Нет видеопотока", color = Color.Gray)
    }
}

@Composable
fun ViewerScreen(session: ViewerSession) {
    val state by session.state.collectAsState()
    val servers by session.servers.collectAsState()
    val output by session.video.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    val active = state.phase !in setOf(ViewerPhase.IDLE, ViewerPhase.ERROR, ViewerPhase.ENDED)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Смотреть трансляцию", style = MaterialTheme.typography.headlineSmall) }
        item {
            VideoPane(output, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Text(state.message, color = if (state.phase == ViewerPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp))
            if (state.frames > 0) Text("${state.fps} FPS · ${state.frames} кадров", style = MaterialTheme.typography.bodySmall)
        }
        if (active) item {
            Button(onClick = { scope.launch { session.disconnect() } }) { Text("Отключиться") }
        }
        item { Text("Код доступа", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(code, { code = it.take(6) }, label = { Text("6 цифр на экране сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !active) }
        item { Text("Серверы в Wi-Fi сети", style = MaterialTheme.typography.titleMedium) }
        if (servers.isEmpty()) item { Text("Поиск трансляций… Можно подключиться по адресу ниже.", style = MaterialTheme.typography.bodyMedium) }
        items(servers, key = { it.id }) { server ->
            OutlinedButton(onClick = { scope.launch { session.connect(server, code) } }, enabled = !active && code.length == 6, modifier = Modifier.fillMaxWidth()) {
                Column { Text(server.name); Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { Text("Подключение по адресу", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(host, { host = it }, label = { Text("IP или IP:порт") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !active) }
        item {
            var validation by remember { mutableStateOf("") }
            OutlinedButton(onClick = {
                val text = host.trim().removePrefix("http://").removePrefix("ws://").trimEnd('/')
                val name = text.substringBefore(':')
                val port = if (':' in text) text.substringAfter(':').toIntOrNull() else 8765
                if (name.isBlank() || port == null || port !in 1..65535) validation = "Укажите адрес и корректный порт"
                else { validation = ""; scope.launch { session.connect(ServerEndpoint("manual:$text", text, name, port), code) } }
            }, enabled = !active && code.length == 6 && host.isNotBlank()) { Text("Подключиться") }
            if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
        }
    }
}
