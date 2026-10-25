package com.callrecall.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.callrecall.R
import com.callrecall.activities.NoteEditorActivity
import kotlin.math.abs
import kotlin.math.hypot

/** Draggable floating overlay shown during active phone calls. */
class FloatingOverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_CallRecall)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var overlayView: View? = null
    private var dismissTarget: View? = null
    private var currentPhoneNumber = ""
    // Only explicit dismissal sets this; call end resets it through hide().
    private var dismissedForCall = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    fun show(phoneNumber: String, hasExistingNote: Boolean) {
        if (!PermissionHelper.hasOverlayPermission(context)) return
        mainHandler.post {
            if (dismissedForCall) return@post
            currentPhoneNumber = phoneNumber
            // Repeated call callbacks should not reset a bubble being dragged.
            overlayView?.let {
                updateIcon(it, hasExistingNote)
                return@post
            }
            try {
                val view = LayoutInflater.from(themedContext)
                    .inflate(R.layout.overlay_floating_button, FrameLayout(themedContext), false)
                updateIcon(view, hasExistingNote)
                val params = overlayParams(context.resources.getDimensionPixelSize(R.dimen.floating_bubble_size)).apply {
                    x = dp(24)
                    y = dp(160)
                }
                view.setOnClickListener { openNoteEditor(currentPhoneNumber) }
                ViewCompat.replaceAccessibilityAction(
                    view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_DISMISS,
                    context.getString(R.string.dismiss_bubble)
                ) { _, _ -> dismissForCurrentCall(); true }
                view.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            dragging = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                dragging = true
                                showDismissTarget()
                            }
                            if (dragging) {
                                val metrics = windowManager.currentWindowMetrics
                                val safe = metrics.windowInsets.getInsetsIgnoringVisibility(
                                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                                )
                                params.x = (initialX + dx.toInt()).coerceIn(
                                    safe.left, maxOf(safe.left, metrics.bounds.width() - safe.right - view.width)
                                )
                                params.y = (initialY + dy.toInt()).coerceIn(
                                    safe.top, maxOf(safe.top, metrics.bounds.height() - safe.bottom - view.height)
                                )
                                try {
                                    windowManager.updateViewLayout(view, params)
                                    dismissTarget?.isActivated = isOverDismissTarget(view)
                                } catch (e: IllegalArgumentException) {
                                    Log.w(TAG, "Overlay detached during drag", e)
                                    hideInternal()
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val dismiss = dragging && isOverDismissTarget(view)
                            hideDismissTarget()
                            if (dismiss) dismissForCurrentCall()
                            else if (!dragging) view.performClick()
                            dragging = false
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            hideDismissTarget()
                            dragging = false
                            true
                        }
                        else -> false
                    }
                }
                windowManager.addView(view, params)
                overlayView = view
            } catch (e: Exception) {
                Log.e(TAG, "addView failed", e)
                hideInternal()
            }
        }
    }

    private fun updateIcon(view: View, hasNote: Boolean) {
        view.findViewById<ImageView>(R.id.floatingButtonIcon).setImageResource(
            if (hasNote) R.drawable.ic_overlay_note else R.drawable.ic_overlay_plus
        )
    }

    @SuppressLint("RtlHardcoded") // Absolute coordinates are required for raw screen touch positions.
    private fun overlayParams(sizePx: Int) = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        PixelFormat.TRANSLUCENT
    ).apply {
        // Absolute left matches raw touch coordinates, including on RTL devices.
        gravity = Gravity.TOP or Gravity.LEFT
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun showDismissTarget() {
        if (dismissTarget != null) return
        try {
            val target = LayoutInflater.from(themedContext)
                .inflate(R.layout.overlay_dismiss_target, FrameLayout(themedContext), false)
            val metrics = windowManager.currentWindowMetrics
            val safe = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val params = overlayParams(dp(64)).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                x = (metrics.bounds.width() - dp(64)) / 2
                y = metrics.bounds.height() - safe.bottom - dp(88)
            }
            windowManager.addView(target, params)
            dismissTarget = target
        } catch (e: Exception) {
            Log.w(TAG, "Cannot show dismiss target", e)
        }
    }

    private fun isOverDismissTarget(bubble: View): Boolean {
        val target = dismissTarget ?: return false
        if (!target.isLaidOut) return false
        val bubbleLocation = IntArray(2)
        val targetLocation = IntArray(2)
        bubble.getLocationOnScreen(bubbleLocation)
        target.getLocationOnScreen(targetLocation)
        val dx = bubbleLocation[0] + bubble.width / 2f - targetLocation[0] - target.width / 2f
        val dy = bubbleLocation[1] + bubble.height / 2f - targetLocation[1] - target.height / 2f
        return hypot(dx, dy) <= target.width / 2f + dp(12)
    }

    private fun dismissForCurrentCall() {
        dismissedForCall = true
        hideInternal()
    }

    /** Called when the call ends; the next call may show a bubble again. */
    fun hide() {
        mainHandler.post {
            hideInternal()
            dismissedForCall = false
        }
    }

    private fun hideDismissTarget() {
        dismissTarget?.let { removeView(it) }
        dismissTarget = null
    }

    private fun hideInternal() {
        hideDismissTarget()
        overlayView?.let { removeView(it) }
        overlayView = null
        dragging = false
    }

    private fun removeView(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // A permission change or system cleanup may already have detached it.
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun openNoteEditor(phoneNumber: String) {
        val intent = Intent(context, NoteEditorActivity::class.java).apply {
            if (phoneNumber.isNotEmpty()) putExtra(NoteEditorActivity.EXTRA_PHONE_NUMBER, phoneNumber)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "FloatingOverlay"
    }
}
