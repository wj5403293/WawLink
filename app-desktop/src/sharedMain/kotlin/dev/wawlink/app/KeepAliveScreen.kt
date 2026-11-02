package dev.wawlink.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.ui.AgentTopBar
import dev.wawlink.ui.ErrorMessage
import dev.wawlink.ui.Symbol

/** Android owns system settings and resources; Desktop never pretends to hold them. */
@Composable
internal fun KeepAliveScreen(app: AppCoordinator, files: HostFiles, onBack: () -> Unit) {
    val settings by app.keepAlive.collectAsState()
    val status by app.keepAliveStatus.collectAsState()
    Column {
        AgentTopBar("后台保活", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (!app.isAndroid) {
                HostRow("仅支持 Android", "桌面端不使用 Android 前台服务、电源锁或自启动设置", "info")
            } else {
                SectionLabel("后台运行", "shield")
                HostRow("后台保活", "开启后，无活动任务时仍保持前台服务", "shield") {
                    Switch(settings.enabled, { app.updateKeepAlive(app.keepAlive.value.copy(enabled = it)) },
                        modifier = Modifier.semantics { contentDescription = "后台保活" })
                }
                HostRow("前台服务", if (status.serviceRunning) "运行中" else "未运行", "notifications_active")
                HostRow("锁屏运行", when {
                    settings.enabled && settings.wakeLock && status.wakeLockHeld -> "已持有 CPU 唤醒锁"
                    settings.enabled && settings.wakeLock -> "CPU 唤醒锁未持有"
                    else -> "熄屏时保持 CPU 运行，不点亮屏幕"
                }, "lock_clock") {
                    Switch(settings.wakeLock, { app.updateKeepAlive(app.keepAlive.value.copy(wakeLock = it)) },
                        enabled = settings.enabled, modifier = Modifier.semantics { contentDescription = "锁屏运行" })
                }
                HostRow("Wi-Fi 网络锁", when {
                    settings.enabled && settings.wifiLock && status.wifiLockHeld -> "已持有 Wi-Fi 锁，仍受系统策略限制"
                    settings.enabled && settings.wifiLock -> "Wi-Fi 锁未持有"
                    else -> "请求减少 Wi-Fi 休眠，不保证网络连接"
                }, "wifi_lock") {
                    Switch(settings.wifiLock, { app.updateKeepAlive(app.keepAlive.value.copy(wifiLock = it)) },
                        enabled = settings.enabled, modifier = Modifier.semantics { contentDescription = "Wi-Fi 网络锁" })
                }
                HostRow("静音音频保活", when {
                    settings.enabled && settings.silentAudio && status.silentAudioPlaying -> "静音音频播放中"
                    settings.enabled && settings.silentAudio -> "静音音频未播放"
                    else -> "循环播放静音音频，不修改系统音量"
                }, "volume_off") {
                    Switch(settings.silentAudio, { app.updateKeepAlive(app.keepAlive.value.copy(silentAudio = it)) },
                        enabled = settings.enabled, modifier = Modifier.semantics { contentDescription = "静音音频保活" })
                }
                SectionLabel("系统设置", "settings")
                HostRow("电池优化", if (status.ignoringBatteryOptimizations) "已忽略电池优化" else "受电池优化限制", "battery_saver",
                    onClick = { runCatching { files.openBatteryOptimizationSettings() }.onFailure(app::showError) }) {
                    Symbol("chevron_right")
                }
                HostRow("自启动与后台运行", "打开当前系统可用的自启动或应用设置", "settings_power",
                    onClick = { runCatching { files.openAutoStartSettings() }.onFailure(app::showError) }) {
                    Symbol("chevron_right")
                }
                status.error?.let { message ->
                    ErrorMessage(message, Modifier.padding(12.dp), fontSize = 16.sp)
                }
                Text("保活会增加耗电，仍受系统及 ROM 限制；强行停止后不会自动恢复。",
                    Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            }
        }
    }
}
