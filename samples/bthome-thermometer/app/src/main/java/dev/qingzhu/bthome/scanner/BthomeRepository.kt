// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.scanner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap

/**
 * 把扫描器的「每条广播」整理成界面想要的「每台设备最新一条」。
 *
 * 同一个地址每秒会来好几条广播，按地址做 key 只保留最新值。
 * 另外每秒推一次快照：没有新广播时也要刷新，才能把 [OFFLINE_AFTER_MS] 没更新的设备标成离线。
 */
class BthomeRepository(context: Context) {

    companion object {
        /** 这么久没收到某台设备的广播，就认为它离线。 */
        const val OFFLINE_AFTER_MS = 10_000L

        /** 离线超过这么久就从列表里移除，避免列表无限变长。 */
        const val DROP_AFTER_MS = 60_000L
    }

    private val scanner = BthomeScanner(context.applicationContext)

    val error get() = scanner.error
    val errorCode get() = scanner.errorCode

    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    fun start(): Boolean = scanner.start()

    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    fun stop() = scanner.stop()

    /** 开始收集时自动开扫描，取消收集时自动停。 */
    val readings: Flow<List<SensorReading>> = callbackFlow {
        val latest = ConcurrentHashMap<String, SensorReading>()
        scanner.readings
            .onEach { r ->
                latest[r.address] = r
                trySend(snapshot(latest))
            }
            .launchIn(this)
        flow {
            while (true) {
                emit(Unit)
                delay(1_000)
            }
        }
            .onEach { trySend(snapshot(latest)) }
            .launchIn(this)

        scanner.start()
        awaitClose { scanner.stop() }
    }.flowOn(Dispatchers.Default)

    private fun snapshot(latest: ConcurrentHashMap<String, SensorReading>): List<SensorReading> {
        val now = android.os.SystemClock.elapsedRealtime()
        val out = ArrayList<SensorReading>(latest.size)
        for ((address, r) in latest) {
            val age = now - r.timestamp
            if (age > DROP_AFTER_MS) {
                latest.remove(address)   // ConcurrentHashMap 允许遍历时删
                continue
            }
            out += if (age > OFFLINE_AFTER_MS) r.copy(online = false) else r
        }
        out.sortByDescending { it.timestamp }
        return out
    }
}
