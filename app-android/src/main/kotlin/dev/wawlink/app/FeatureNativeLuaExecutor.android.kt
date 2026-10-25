package dev.wawlink.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun rememberFeatureNativeLuaExecutor(key: String?): FeatureNativeLuaExecutor {
    val context = LocalContext.current
    val executor = remember(context, key) { AndroidFeatureNativeLuaExecutor(context) }
    DisposableEffect(executor) { onDispose { executor.close() } }
    return executor
}

private class AndroidFeatureNativeLuaExecutor(context: Context) : FeatureNativeLuaExecutor {
    private val closed = AtomicBoolean()
    private val runtime = FeatureAndroidLuaRuntime(context)
    private val lock = Any()
    private val tasks = mutableSetOf<FutureTask<Unit>>()
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>()) { task ->
        Thread(task, "wawlink-native-feature-lua").apply { isDaemon = true }
    }

    override val available: Boolean get() = !closed.get()

    override suspend fun execute(source: String): JsonElement = suspendCancellableCoroutine { continuation ->
        fun ensureActive() {
            if (closed.get() || !continuation.isActive || Thread.currentThread().isInterrupted) throw FeatureLuaDisposed()
        }
        val task = object : FutureTask<Unit>({
            try {
                ensureActive()
                val result = runtime.newSession(::ensureActive).use { it.evaluate(source) }
                ensureActive()
                continuation.resumeWith(Result.success(result))
            } catch (failure: Throwable) {
                continuation.resumeWith(Result.failure(failure))
            }
            Unit
        }) {
            override fun done() {
                synchronized(lock) { tasks.remove(this) }
                worker.remove(this)
                if (isCancelled) continuation.cancel(CancellationException("Native Lua execution disposed"))
            }
        }
        continuation.invokeOnCancellation { task.cancel(true) }
        synchronized(lock) {
            if (closed.get()) {
                task.cancel(true)
            } else if (!task.isCancelled) {
                tasks.add(task)
                worker.execute(task)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            tasks.toList().forEach { it.cancel(true) }
            tasks.clear()
            worker.shutdownNow()
        }
        runtime.close()
    }
}
