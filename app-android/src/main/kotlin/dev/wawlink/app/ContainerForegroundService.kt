package dev.wawlink.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
    private var observation: Job? = null
    private var idleStop: Job? = null
    private var lastStartId = 0
    private var foregroundStartFailed = false
    private var wakeRequested = false
    private var wifiRequested = false
    private var audioRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var silentAudio: AudioTrack? = null
    private val errors = linkedMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        (application as WawLinkApplication).onContainerServiceCreated(this)
        // No settings, resources or idle shutdown before the system's foreground handshake.
        ensureForeground(withAudio = false, force = true)
        foregroundStartFailed = !foregroundRunning
        publishStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        idleStop?.cancel()
        idleStop = null
        val settings = coordinator.keepAlive.value
        // A new startForegroundService may target an instance already winding down.
        // Its command needs a new handshake even if onCreate ran (or foreground was removed).
        ensureForeground(settings.enabled && settings.silentAudio, force = true)
        foregroundStartFailed = !foregroundRunning
        if (foregroundStartFailed) {
            releaseResources()
            publishStatus()
            // The OS rejected every legal promotion. Do not invent another type or restart-loop.
            // Client preflight prevents known manifest/permission failures before requesting FGS.
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        reconcile()
        observeState()
        return if (coordinator.keepAlive.value.enabled) START_STICKY else START_NOT_STICKY
    }

    private fun reconcile() {
        if (lastStartId == 0 || foregroundStartFailed) return
        // Read current values, not a queued flow emission, before releasing or stopping.
        val settings = coordinator.keepAlive.value
        val wantsAudio = settings.enabled && settings.silentAudio
        if (!wantsAudio) updateAudio(false)
        val canPlayAudio = ensureForeground(wantsAudio)
        if (!foregroundRunning) {
            foregroundStartFailed = true
            releaseResources()
            publishStatus()
            stopSelfResult(lastStartId)
            return
        }
        updateWakeLock(settings.enabled && settings.wakeLock)
        updateWifiLock(settings.enabled && settings.wifiLock)
        if (wantsAudio) updateAudio(canPlayAudio)
        publishStatus()
        if (coordinator.keepAlive.value.enabled || coordinator.active.value) {
            idleStop?.cancel()
            idleStop = null
        } else if (idleStop == null) {
            idleStop = scope.launch {
                // Coalesce short jobs on the STOP side; never delay initial FGS eligibility.
                delay(IDLE_STOP_DELAY_MS)
                idleStop = null
                if (coordinator.keepAlive.value.enabled || coordinator.active.value) return@launch
                releaseResources()
                if (coordinator.keepAlive.value.enabled || coordinator.active.value) return@launch
                // Check the system's latest startId before removing its notification.
                if (stopSelfResult(lastStartId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foregroundRunning = false
                    foregroundTypes = null
                    publishStatus()
                }
            }
        }
    }

    private fun ensureForeground(withAudio: Boolean, force: Boolean = false): Boolean {
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val types = base or (if (withAudio && Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        if (!force && attemptedForegroundTypes == types) return foregroundRunning && foregroundTypes == types
        attemptedForegroundTypes = types
        errors.remove(FOREGROUND)
        errors.remove(AUDIO_FOREGROUND)
        if (!::notification.isInitialized && !prepareNotification()) return false
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            foregroundTypes = types
            foregroundRunning = true
            true
        } catch (e: Exception) {
            if (types == base) {
                recordFailure(FOREGROUND, e)
            } else {
                recordFailure(AUDIO_FOREGROUND, e)
                updateAudio(false)
                // Only the optional audio type can be dropped; API 34+ still requires specialUse.
                if (force || !foregroundRunning || foregroundTypes != base) {
                    try {
                        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, base)
                        foregroundTypes = base
                        foregroundRunning = true
                    } catch (fallback: Exception) {
                        recordFailure(FOREGROUND, fallback)
                    }
                }
            }
            false
        }
    }
    private fun prepareNotification(): Boolean {
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
            recordFailure(NOTIFICATION, e)
            try {
                // A real minimal notification: no resource-string lookup or PendingIntent needed.
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "WawLink 后台服务", NotificationManager.IMPORTANCE_LOW))
                notification = Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("WawLink 后台服务")
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build()
            } catch (fallback: Exception) {
                recordFailure(NOTIFICATION, fallback)
                return false
            }
        }
        return true
    }

    private fun observeState() {
        // A command reconciles synchronously; a stale collector can conflate a quick on/off pair.
        observation?.cancel()
        var previousEnabled = coordinator.keepAlive.value.enabled
        observation = scope.launch {
            combine(coordinator.keepAlive, coordinator.active) { _, _ -> Unit }.collect {
                val enabled = coordinator.keepAlive.value.enabled
                val changed = enabled != previousEnabled
                previousEnabled = enabled
                reconcile()
                if (changed && foregroundRunning && (enabled || coordinator.active.value)) {
                    try {
                        // Update Android's last start-command result when sticky policy changes.
                        startService(Intent(this@ContainerForegroundService, ContainerForegroundService::class.java))
                    } catch (e: Exception) {
                        recordFailure(FOREGROUND, e)
                        publishStatus()
                    }
                }
            }
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
        (application as WawLinkApplication).onContainerServiceDestroyed(this, restartIfNeeded = !foregroundStartFailed)
    }

    companion object {
        private const val CHANNEL = "wawlink_container_processes"
        private const val NOTIFICATION_ID = 1001
        private const val SILENT_FRAMES = 44_100
        private const val IDLE_STOP_DELAY_MS = 300L
        private const val NOTIFICATION = "服务通知"
        private const val AUDIO_FOREGROUND = "静音播放前台类型"
        private const val FOREGROUND = "前台服务"
        private const val WAKE = "CPU 唤醒锁"
        private const val WIFI = "Wi-Fi 锁"
        private const val AUDIO = "静音播放"

        internal fun validateStartConfiguration(context: Context) {
            fun requirePermission(permission: String) {
                check(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                    "前台服务缺少权限：$permission"
                }
            }
            if (Build.VERSION.SDK_INT >= 28) requirePermission(Manifest.permission.FOREGROUND_SERVICE)
            if (Build.VERSION.SDK_INT >= 34) {
                requirePermission(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
                val info = context.packageManager.getServiceInfo(ComponentName(context, ContainerForegroundService::class.java), 0)
                check(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0) {
                    "前台服务未声明 specialUse 类型"
                }
            }
        }
    }
}
