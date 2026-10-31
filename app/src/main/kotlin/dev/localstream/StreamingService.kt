package dev.localstream

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.*

class StreamingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = application as StreamApplication
    private var stopping = false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("stream", "Трансляция камеры", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopping = true
            scope.launch { graph.host.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "stream").setSmallIcon(dev.localstream.app.R.drawable.ic_stream)
            .setContentTitle("Трансляция камеры").setContentText("Local Camera Stream · локальная Wi-Fi сеть")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        scope.launch {
            try { graph.host.start(graph.camera(intent?.getStringExtra("backend") ?: "camera2")) }
            catch (_: Exception) { stopping = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (!stopping) scope.launch { try { graph.host.stop() } finally { scope.cancel() } }
        else scope.cancel()
        super.onDestroy()
    }
}
