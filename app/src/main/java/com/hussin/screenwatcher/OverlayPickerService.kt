package com.hussin.screenwatcher

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/**
 * خدمة تعرض نافذة شفافة فوق كل التطبيقات (تحتاج صلاحية "Display over other apps").
 * تستخدم لمرحلتين بالترتيب:
 *  1) PICK_AREA: المستخدم يسحب لتحديد مستطيل القراءة (مكان القيمة على الشاشة)
 *  2) PICK_POINT: المستخدم ينقر نقطة واحدة (مكان زر "التالي")
 */
class OverlayPickerService : Service() {

    enum class Mode { PICK_AREA, PICK_POINT }

    private lateinit var windowManager: WindowManager
    private var overlayView: FrameLayout? = null
    private var selectionBox: View? = null
    private var instructionText: TextView? = null

    private var mode: Mode = Mode.PICK_AREA
    private var startX = 0f
    private var startY = 0f
    private var currentLeft = 0
    private var currentTop = 0
    private var currentRight = 0
    private var currentBottom = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = when (intent?.getStringExtra(EXTRA_MODE)) {
            "POINT" -> Mode.PICK_POINT
            else -> Mode.PICK_AREA
        }
        showOverlay()
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val root = FrameLayout(this)
        overlayView = root

        // مربع التحديد المرئي (شفاف أحمر الحدود)
        val box = View(this).apply {
            setBackgroundColor(Color.argb(60, 255, 0, 0))
        }
        selectionBox = box
        root.addView(box, FrameLayout.LayoutParams(0, 0))

        // نص توجيهي بالأعلى
        val text = TextView(this).apply {
            text = if (mode == Mode.PICK_AREA)
                "اسحب لتحديد منطقة القراءة (اضغط زر تأكيد بعد التحديد)"
            else
                "انقر على مكان زر \"التالي\" بالضبط"
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.WHITE)
            setPadding(20, 20, 20, 20)
            textSize = 14f
        }
        instructionText = text
        root.addView(
            text,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 60 }
        )

        // زر تأكيد (يظهر فقط بعد تحديد منطقة)
        val confirmBtn = Button(this).apply {
            text = "تأكيد"
            visibility = if (mode == Mode.PICK_POINT) View.GONE else View.GONE
            setOnClickListener { confirmAreaSelection() }
        }
        root.addView(
            confirmBtn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 80
            }
        )
        this.confirmButton = confirmBtn

        root.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(root, params)
    }

    private var confirmButton: Button? = null

    private fun handleTouch(event: MotionEvent) {
        when (mode) {
            Mode.PICK_AREA -> handleAreaTouch(event)
            Mode.PICK_POINT -> handlePointTouch(event)
        }
    }

    private fun handleAreaTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                updateSelectionBox(startX, startY, event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP -> {
                updateSelectionBox(startX, startY, event.rawX, event.rawY)
                confirmButton?.visibility = View.VISIBLE
            }
        }
    }

    private fun handlePointTouch(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            broadcastPointResult(x, y)
            stopSelf()
        }
    }

    private fun updateSelectionBox(x1: Float, y1: Float, x2: Float, y2: Float) {
        currentLeft = minOf(x1, x2).toInt()
        currentTop = minOf(y1, y2).toInt()
        currentRight = maxOf(x1, x2).toInt()
        currentBottom = maxOf(y1, y2).toInt()

        val lp = FrameLayout.LayoutParams(currentRight - currentLeft, currentBottom - currentTop)
        lp.leftMargin = currentLeft
        lp.topMargin = currentTop
        selectionBox?.layoutParams = lp
        selectionBox?.requestLayout()
    }

    private fun confirmAreaSelection() {
        if (currentRight > currentLeft && currentBottom > currentTop) {
            broadcastAreaResult(currentLeft, currentTop, currentRight, currentBottom)
        }
        stopSelf()
    }

    private fun broadcastAreaResult(left: Int, top: Int, right: Int, bottom: Int) {
        val intent = Intent(ACTION_AREA_PICKED).apply {
            putExtra("left", left)
            putExtra("top", top)
            putExtra("right", right)
            putExtra("bottom", bottom)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastPointResult(x: Int, y: Int) {
        val intent = Intent(ACTION_POINT_PICKED).apply {
            putExtra("x", x)
            putExtra("y", y)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* already removed */ }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val ACTION_AREA_PICKED = "com.hussin.screenwatcher.AREA_PICKED"
        const val ACTION_POINT_PICKED = "com.hussin.screenwatcher.POINT_PICKED"
    }
}
