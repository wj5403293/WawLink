package dev.wawlink.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

class ContainerForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator get() = (application as WawLinkApplication).coordinator
    private lateinit var notification: Notification
    private var foregroundRunning = false
    private var foregroundTypes: Int? = null
    private var attemptedForegroundTypes: Int? = null
    private var wakeRequested = false
    private var wifiRequested = false
    private var audioRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var silentAudio: AudioTrack? = null
    private val errors = linkedMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.container_channel), NotificationManager.IMPORTANCE_LOW))
            val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("WawLink 后台服务")
                .setContentText("后台服务运行中，点击返回应用。")
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSilent(true)
                .build()
        } catch (e: Exception) {
            recordFailure(FOREGROUND, e)
            publishStatus()
            stopSelf()
            return
        }
        val settings = coordinator.keepAlive.value
        if (!ensureForeground(settings.enabled && settings.silentAudio)) {
            publishStatus()
            stopSelf()
            return
        }
        scope.launch {
            var previousEnabled = coordinator.keepAlive.value.enabled
            combine(coordinator.keepAlive, coordinator.active) { _, _ -> Unit }.collect {
                val enabled = coordinator.keepAlive.value.enabled
                val changed = enabled != previousEnabled
                previousEnabled = enabled
                reconcile()
                if (changed && foregroundRunning && (coordinator.keepAlive.value.enabled || coordinator.active.value)) {
                    try {
                        // Android remembers the last start-command result; settings alone cannot change it.
                        startService(Intent(this@ContainerForegroundService, ContainerForegroundService::class.java))
                    } catch (e: Exception) {
                        recordFailure(FOREGROUND, e)
                        publishStatus()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundRunning) reconcile()
        return if (coordinator.keepAlive.value.enabled) START_STICKY else START_NOT_STICKY
    }

    private fun reconcile() {
        // Read current values, not a queued flow emission, before releasing or stopping.
        val settings = coordinator.keepAlive.value
        val wantsAudio = settings.enabled && settings.silentAudio
        if (!wantsAudio) updateAudio(false)
        val canPlayAudio = ensureForeground(wantsAudio)
        updateWakeLock(settings.enabled && settings.wakeLock)
        updateWifiLock(settings.enabled && settings.wifiLock)
        if (wantsAudio) updateAudio(canPlayAudio)
        publishStatus()
        if (!coordinator.keepAlive.value.enabled && !coordinator.active.value) {
            releaseResources()
            if (coordinator.keepAlive.value.enabled || coordinator.active.value) {
                publishStatus()
                return
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundRunning = false
            publishStatus()
            stopSelf()
        }
    }

    private fun ensureForeground(withAudio: Boolean): Boolean {
        val types = (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
            (if (withAudio && Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        if (attemptedForegroundTypes == types) return foregroundRunning && foregroundTypes == types
        attemptedForegroundTypes = types
        errors.remove(FOREGROUND)
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            foregroundTypes = types
            foregroundRunning = true
            true
        } catch (e: Exception) {
            recordFailure(FOREGROUND, e)
            false
        }
    }

    private fun updateWakeLock(requested: Boolean) {
        if (wakeRequested == requested) return
        wakeRequested = requested
        errors.remove(WAKE)
        if (!requested) {
            releaseWakeLock()
            return
        }
        try {
            check(wakeLock == null) { "前一个 CPU 唤醒锁尚未释放" }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:keepAlive").apply {
                    setReferenceCounted(false)
                }
            wakeLock!!.acquire()
            check(wakeLock!!.isHeld) { "未能获取 CPU 唤醒锁" }
        } catch (e: Exception) {
            recordFailure(WAKE, e)
            releaseWakeLock()
        }
    }

    @Suppress("DEPRECATION")
    private fun updateWifiLock(requested: Boolean) {
        if (wifiRequested == requested) return
        wifiRequested = requested
        errors.remove(WIFI)
        if (!requested) {
            releaseWifiLock()
            return
        }
        try {
            check(wifiLock == null) { "前一个 Wi-Fi 锁尚未释放" }
            val manager = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:keepAlive").apply {
                setReferenceCounted(false)
            }
            wifiLock!!.acquire()
            check(wifiLock!!.isHeld) { "未能获取 Wi-Fi 锁" }
        } catch (e: Exception) {
            recordFailure(WIFI, e)
            releaseWifiLock()
        }
    }

    private fun updateAudio(requested: Boolean) {
        if (audioRequested == requested) return
        audioRequested = requested
        errors.remove(AUDIO)
        if (!requested) {
            releaseAudio()
            return
        }
        try {
            check(silentAudio == null) { "前一个静音音轨尚未释放" }
            // A real, looping PCM buffer; no audio focus request or device volume changes.
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SILENT_FRAMES)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(SILENT_FRAMES * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            silentAudio = track
            val samples = ShortArray(SILENT_FRAMES)
            check(track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) == samples.size) { "无法写入静音音频缓冲区" }
            check(track.state == AudioTrack.STATE_INITIALIZED) { "静音音轨未初始化" }
            check(track.setLoopPoints(0, SILENT_FRAMES, -1) == AudioTrack.SUCCESS) { "无法配置静音音频循环" }
            track.play()
            check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "静音音频未能开始播放" }
        } catch (e: Exception) {
            recordFailure(AUDIO, e)
            releaseAudio()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) lock.release()
            wakeLock = null
        } catch (e: Exception) {
            recordFailure(WAKE, e)
        }
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        try {
            if (lock.isHeld) lock.release()
            wifiLock = null
        } catch (e: Exception) {
            recordFailure(WIFI, e)
        }
    }

    private fun releaseAudio() {
        val track = silentAudio ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        } catch (e: Exception) {
            recordFailure(AUDIO, e)
        }
        try {
            track.release()
            silentAudio = null
        } catch (e: Exception) {
            recordFailure(AUDIO, e)
        }
    }

    private fun releaseResources() {
        wakeRequested = false
        wifiRequested = false
        audioRequested = false
        releaseAudio()
        releaseWifiLock()
        releaseWakeLock()
    }

    private fun recordFailure(resource: String, error: Exception) {
        val detail = error.message ?: error.javaClass.simpleName
        errors[resource] = errors[resource]?.let { "$it; $detail" } ?: detail
    }

    private fun publishStatus() {
        val running = foregroundRunning
        val wakeHeld = wakeLock?.isHeld == true
        val wifiHeld = wifiLock?.isHeld == true
        val audioPlaying = silentAudio?.playState == AudioTrack.PLAYSTATE_PLAYING
        val error = errors.takeIf { it.isNotEmpty() }?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }
        coordinator.updateKeepAliveStatus {
            it.copy(serviceRunning = running, wakeLockHeld = wakeHeld, wifiLockHeld = wifiHeld, silentAudioPlaying = audioPlaying, error = error)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseResources()
        foregroundRunning = false
        publishStatus()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "wawlink_container_processes"
        private const val NOTIFICATION_ID = 1001
        private const val SILENT_FRAMES = 44_100
        private const val FOREGROUND = "前台服务"
        private const val WAKE = "CPU 唤醒锁"
        private const val WIFI = "Wi-Fi 锁"
        private const val AUDIO = "静音播放"
    }
}
