package me.excuse.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import me.excuse.app.ui.theme.ExcuseTheme

/**
 * 悬浮倒计时圆环窗口：一个小的、可拖动、可触摸的 overlay，挂在屏幕左侧中上贴边。
 *
 * 与全屏拦截窗 [OverlayManager] 完全独立——那个是 MATCH_PARENT 全屏吃触摸的；
 * 这个只有约 56dp 大小，不抢焦点（FLAG_NOT_FOCUSABLE），触摸仅用于拖动圆环本身。
 *
 * 抗冻结关键：倒计时刷新用 [Handler.postDelayed]（消息队列机制），而非协程 delay；
 * 剩余时间按绝对时间戳（wall clock deadline）计算，即使中途被冻结，恢复后也能立刻
 * 显示正确剩余，不会卡住或跳变。
 */
class CountdownOverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var rootView: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    private var remainingMinutes by mutableIntStateOf(0)
    private var progress by mutableFloatStateOf(0f)

    // 到点回调（圆环归零时触发，用于弹原拦截窗）。
    private var onDeadline: (() -> Unit)? = null

    private var deadlineMillis: Long = 0L
    private var plannedMinutes: Int = 0
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateFromDeadline()
            if (deadlineMillis > System.currentTimeMillis()) {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    /**
     * 显示圆环并开始倒计时。
     *
     * @param plannedMinutes 计划分钟数。
     * @param deadlineMillis 到点的绝对墙钟时间戳（startedAt + plannedMinutes * 60_000）。
     * @param onDeadline 圆环归零时回调（弹原拦截窗）。
     */
    fun show(plannedMinutes: Int, deadlineMillis: Long, onDeadline: () -> Unit) {
        this.plannedMinutes = plannedMinutes
        this.deadlineMillis = deadlineMillis
        this.onDeadline = onDeadline
        updateFromDeadline()
        ensureWindow()
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    fun dismiss() {
        handler.removeCallbacks(tickRunnable)
        onDeadline = null
        deadlineMillis = 0L
        rootView?.let { root ->
            try {
                if (root.isAttachedToWindow) wm.removeViewImmediate(root)
            } catch (_: Exception) {
            }
        }
        rootView = null
        layoutParams = null
        try {
            lifecycleOwner?.stop()
        } catch (_: Exception) {
        }
        lifecycleOwner = null
    }

    fun release() {
        dismiss()
    }

    /** 根据绝对时间戳重算剩余分钟和进度；到点则触发回调。 */
    private fun updateFromDeadline() {
        val now = System.currentTimeMillis()
        val remainingMs = deadlineMillis - now
        if (remainingMs <= 0L) {
            remainingMinutes = 1
            progress = 1f
            val cb = onDeadline
            onDeadline = null
            handler.removeCallbacks(tickRunnable)
            dismiss()
            cb?.invoke()
            return
        }
        // 分钟级向上取整：剩 4 分 1 秒 → 显示 5；剩 30 秒 → 显示 1。
        val remainingMin = ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        remainingMinutes = remainingMin
        val totalMs = plannedMinutes * 60_000L
        progress = if (totalMs > 0L) {
            (1f - remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    private fun ensureWindow() {
        if (rootView?.isAttachedToWindow == true) return
        try {
            val cv = ComposeView(context)
            val root = FrameLayout(context)
            root.addView(
                cv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
            )

            // Compose 挂进 WindowManager 必须先提供 LifecycleOwner 三件套（同 OverlayManager），
            // 否则首次组合在 Choreographer 帧回调里崩 "ViewTreeLifecycleOwner not found"，
            // 该异常不在本 try-catch 范围，会直接闪退整个进程（前台服务和通知随之消失）。
            val owner = OverlayLifecycleOwner()
            owner.attachToView(root)
            owner.attachToView(cv)
            owner.start()

            cv.setContent {
                ExcuseTheme {
                    CountdownRing(
                        remainingMinutes = remainingMinutes,
                        progress = progress,
                    )
                }
            }

            val params = makeParams()
            wm.addView(root, params)
            rootView = root
            layoutParams = params
            lifecycleOwner = owner
            attachDragGesture(root, params)
        } catch (e: Exception) {
            Log.w(TAG, "countdown overlay addView failed", e)
        }
    }

    /** 拖动圆环：按下移动时更新 window 的 x/y，实现可拖动。 */
    private fun attachDragGesture(root: View, params: WindowManager.LayoutParams) {
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (Math.abs(dx) > TOUCH_SLOP || Math.abs(dy) > TOUCH_SLOP)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(root, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun makeParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val size = dpToPx(RING_DP)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return WindowManager.LayoutParams(
            size,
            size,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 左侧中上贴边：屏幕左侧留 8dp 边距，纵向约 1/4 高度处。
            x = dpToPx(EDGE_MARGIN_DP)
            y = dpToPx(EDGE_MARGIN_DP) * 6
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ReasonTimer"
        const val RING_DP = 45
        const val EDGE_MARGIN_DP = 8
        const val TICK_MS = 1000L
        const val TOUCH_SLOP = 8f
    }
}
