package com.hussin.screenwatcher

import android.content.Context
import android.content.SharedPreferences

/**
 * يخزن إعدادات المستخدم: منطقة القراءة، نقطة النقر، القيمة المستهدفة، نوع المقارنة.
 * كل القيم تُحفظ في SharedPreferences بسيطة (بدون أي بيانات شخصية أو حساسة).
 */
enum class CompareType { GREATER, LESS, EQUAL }

data class WatcherConfig(
    var readLeft: Int = -1,
    var readTop: Int = -1,
    var readRight: Int = -1,
    var readBottom: Int = -1,
    var tapX: Int = -1,
    var tapY: Int = -1,
    var targetValue: Double = 0.0,
    var compareType: CompareType = CompareType.EQUAL
) {
    fun isReadAreaSet() = readLeft >= 0 && readTop >= 0 && readRight > readLeft && readBottom > readTop
    fun isTapPointSet() = tapX >= 0 && tapY >= 0
    fun isComplete() = isReadAreaSet() && isTapPointSet()
}

object ConfigStore {
    private const val PREFS = "screen_watcher_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, config: WatcherConfig) {
        prefs(context).edit().apply {
            putInt("readLeft", config.readLeft)
            putInt("readTop", config.readTop)
            putInt("readRight", config.readRight)
            putInt("readBottom", config.readBottom)
            putInt("tapX", config.tapX)
            putInt("tapY", config.tapY)
            putFloat("targetValue", config.targetValue.toFloat())
            putString("compareType", config.compareType.name)
            apply()
        }
    }

    fun load(context: Context): WatcherConfig {
        val p = prefs(context)
        return WatcherConfig(
            readLeft = p.getInt("readLeft", -1),
            readTop = p.getInt("readTop", -1),
            readRight = p.getInt("readRight", -1),
            readBottom = p.getInt("readBottom", -1),
            tapX = p.getInt("tapX", -1),
            tapY = p.getInt("tapY", -1),
            targetValue = p.getFloat("targetValue", 0f).toDouble(),
            compareType = try {
                CompareType.valueOf(p.getString("compareType", CompareType.EQUAL.name)!!)
            } catch (e: Exception) {
                CompareType.EQUAL
            }
        )
    }

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean("isRunning", running).apply()
    }

    fun isRunning(context: Context): Boolean =
        prefs(context).getBoolean("isRunning", false)
}
