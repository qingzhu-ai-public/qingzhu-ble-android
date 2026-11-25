// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.advertiser

import dev.qingzhu.bthome.protocol.Measurement
import dev.qingzhu.bthome.protocol.Obj
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin

/** 假数据源。广播代码只认这个接口，换数据源时广播和界面一行都不用改。 */
interface SensorSource {
    val readings: Flow<List<Measurement>>
}

/** ① 正弦波：看起来最像真房间，每秒变一次。 */
class WaveSource : SensorSource {
    override val readings = flow {
        var t = 0.0
        while (true) {
            emit(
                listOf(
                    Measurement(Obj.TEMPERATURE, (23 + 3 * sin(t)).toFloat()),
                    Measurement(Obj.HUMIDITY, (50 + 8 * sin(t * 0.7)).toFloat()),
                )
            )
            t += 0.05
            delay(1000)
        }
    }
}

/** ② 手动滑杆：拉到零下、拉到 0，专门验证符号扩展和零值显示。 */
class ManualSource : SensorSource {
    private val state = MutableStateFlow(
        listOf(
            Measurement(Obj.TEMPERATURE, 25.1f),
            Measurement(Obj.HUMIDITY, 45.2f),
        )
    )

    val current: StateFlow<List<Measurement>> = state.asStateFlow()
    override val readings: Flow<List<Measurement>> = current

    val temperature: Float get() = state.value.first { it.obj == Obj.TEMPERATURE }.value
    val humidity: Float get() = state.value.first { it.obj == Obj.HUMIDITY }.value

    fun setTemperature(v: Float) = replace(Obj.TEMPERATURE, v)
    fun setHumidity(v: Float) = replace(Obj.HUMIDITY, v)

    private fun replace(obj: Obj, v: Float) {
        state.value = current.value.map { if (it.obj == obj) Measurement(obj, v) else it }
    }
}

/** ③ 剧本：按固定节奏走一遍边界值，最后发一个越界湿度，专门复现解析坑。 */
class ScriptSource : SensorSource {
    override val readings = flow {
        val steps = listOf(-5.50f, 0f, 25.06f, 40f, -40f)      // 温度边界，循环播放
        while (true) {
            for (v in steps) {
                emit(listOf(Measurement(Obj.TEMPERATURE, v)))
                delay(3000)
            }
            // 每走完一遍边界值，发一次 105%，主端应该标红而不是直接相信
            emit(listOf(Measurement(Obj.HUMIDITY, 105f)))
            delay(3000)
        }
    }
}
