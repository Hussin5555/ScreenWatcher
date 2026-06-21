package com.hussin.screenwatcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import java.util.regex.Pattern

/**
 * الخدمة الأساسية:
 * - تمسح شجرة عناصر الشاشة الحالية
 * - تستخرج أول نص رقمي يقع داخل منطقة القراءة المحددة
 * - تقارنه بالقيمة المستهدفة حسب نوع المقارنة (>, <, =)
 * - لو تطابق الشرط: توقف نفسها (تنهي المراقبة)
 * - لو لم يتطابق: تنقر على نقطة "التالي" المحددة، وتنتظر قليلاً ثم تعيد المحاولة
 */
class ScreenWatcherService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var config: WatcherConfig = WatcherConfig()
    private var isActive = false
    private var isProcessing = false

    // فترة الانتظار بعد كل نقرة قبل إعادة فحص الشاشة (ميلي ثانية)
    private val checkIntervalMs = 1200L

    override fun onServiceConnected() {
        super.onServiceConnected()
        config = ConfigStore.load(this)
        isActive = ConfigStore.isRunning(this)
        createNotificationChannel()
        if (isActive && config.isComplete()) {
            startForegroundNotice()
            scheduleCheck()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // الفحص يتم عبر الحلقة المجدولة (scheduleCheck) لا عبر كل حدث،
        // لتجنّب الفحص العشوائي الكثير وقت تغييرات الواجهة غير المرتبطة.
    }

    override fun onInterrupt() {
        stopWatching()
    }

    fun startWatching() {
        config = ConfigStore.load(this)
        if (!config.isComplete()) return
        isActive = true
        ConfigStore.setRunning(this, true)
        startForegroundNotice()
        scheduleCheck()
    }

    fun stopWatching() {
        isActive = false
        ConfigStore.setRunning(this, false)
        handler.removeCallbacksAndMessages(null)
        stopForeground(true)
    }

    private fun scheduleCheck() {
        if (!isActive) return
        handler.postDelayed({ performCheck() }, checkIntervalMs)
    }

    private fun performCheck() {
        if (!isActive || isProcessing) return
        isProcessing = true

        val root = rootInActiveWindow
        if (root == null) {
            isProcessing = false
            scheduleCheck()
            return
        }

        val readRect = Rect(config.readLeft, config.readTop, config.readRight, config.readBottom)
        val foundValue = findNumericValueInArea(root, readRect)
        root.recycle()

        if (foundValue == null) {
            // لم يُعثر على قيمة رقمية ضمن المنطقة، اعتبرها عدم تطابق وانقر التالي
            tapNextAndContinue()
            return
        }

        val matched = when (config.compareType) {
            CompareType.GREATER -> foundValue > config.targetValue
            CompareType.LESS -> foundValue < config.targetValue
            CompareType.EQUAL -> foundValue == config.targetValue
        }

        if (matched) {
            notifyMatchFound(foundValue)
            stopWatching()
            isProcessing = false
        } else {
            tapNextAndContinue()
        }
    }

    private fun tapNextAndContinue() {
        performTap(config.tapX, config.tapY) {
            isProcessing = false
            scheduleCheck()
        }
    }

    /**
     * يمسح شجرة العناصر بحثاً عن أول نص رقمي تتقاطع حدوده مع منطقة القراءة.
     */
    private fun findNumericValueInArea(node: AccessibilityNodeInfo, area: Rect): Double? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (Rect.intersects(bounds, area)) {
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (!text.isNullOrBlank()) {
                val value = extractNumber(text)
                if (value != null) return value
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNumericValueInArea(child, area)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    /**
     * يستخرج أول رقم من نص قد يحتوي فواصل أو رموز اختصار (K/M) مثل "12.3K" أو "1,200".
     */
    private fun extractNumber(text: String): Double? {
        val cleaned = text.trim()
        val pattern = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*([kKmMبB]?)")
        val matcher = pattern.matcher(cleaned)
        if (matcher.find()) {
            val numberPart = matcher.group(1)?.replace(",", "") ?: return null
            val suffix = matcher.group(2)?.lowercase() ?: ""
            val base = numberPart.toDoubleOrNull() ?: return null
            return when (suffix) {
                "k" -> base * 1_000
                "m" -> base * 1_000_000
                "b" -> base * 1_000_000_000
                else -> base
            }
        }
        return null
    }

    private fun performTap(x: Int, y: Int, onDone: () -> Unit) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone()
            }
        }, null)

        if (!dispatched) {
            // فشل إرسال اللفتة، حاول مرة أخرى بالحلقة العادية
            onDone()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Watcher", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotice() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("مراقبة الشاشة تعمل")
            .setContentText("يبحث عن القيمة المحددة...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun notifyMatchFound(value: Double) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("تم العثور على القيمة المطلوبة")
            .setContentText("القيمة التي وُجدت: $value")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID + 1, notification)

        // إشعار الواجهة الرئيسية (لو مفتوحة) عبر broadcast
        sendBroadcast(Intent(ACTION_MATCH_FOUND).apply {
            putExtra("value", value)
            setPackage(packageName)
        })
    }

    companion object {
        const val CHANNEL_ID = "screen_watcher_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_MATCH_FOUND = "com.hussin.screenwatcher.MATCH_FOUND"

        // مرجع ثابت للخدمة الحالية للسماح للواجهة الرئيسية باستدعاء start/stop مباشرة
        var instance: ScreenWatcherService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
