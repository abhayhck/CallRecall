package com.callrecall.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.callrecall.R
import com.callrecall.activities.NoteEditorActivity
import kotlin.math.abs

/**
 * Draggable floating overlay shown during active phone calls.
 */
class FloatingOverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_CallRecall)
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentPhoneNumber: String = ""

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    @SuppressLint("ClickableViewAccessibility")
    fun show(phoneNumber: String, hasExistingNote: Boolean) {
        if (!PermissionHelper.hasOverlayPermission(context)) {
            Log.w(TAG, "Overlay permission not granted")
            return
        }

        currentPhoneNumber = phoneNumber

        mainHandler.post {
            try {
                hideInternal()
                val inflater = LayoutInflater.from(themedContext)
                val view = inflater.inflate(R.layout.overlay_floating_button, null)
                val icon = view.findViewById<ImageView>(R.id.floatingButtonIcon)
                icon.setImageResource(
                    if (hasExistingNote) R.drawable.ic_overlay_note else R.drawable.ic_overlay_plus
                )

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 48
                    y = 320
                    alpha = 1f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }

                view.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(view, params)
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val moved = abs(event.rawX - initialTouchX) > 10 ||
                                abs(event.rawY - initialTouchY) > 10
                            if (!moved) {
                                openNoteEditor(currentPhoneNumber)
                            }
                            true
                        }
                        else -> false
                    }
                }

                windowManager.addView(view, params)
                overlayView = view
                layoutParams = params
                view.post {
                    Log.i(
                        TAG,
                        "Overlay shown (${view.width}x${view.height}) at (${params.x}, ${params.y})"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "addView failed", e)
            }
        }
    }

    fun hide() {
        mainHandler.post { hideInternal() }
    }

    private fun hideInternal() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
                Log.i(TAG, "Overlay hidden")
            } catch (_: Exception) {
            }
        }
        overlayView = null
        layoutParams = null
    }

    private fun openNoteEditor(phoneNumber: String) {
        val intent = Intent(context, NoteEditorActivity::class.java).apply {
            if (phoneNumber.isNotEmpty()) {
                putExtra(NoteEditorActivity.EXTRA_PHONE_NUMBER, phoneNumber)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "FloatingOverlay"
    }
}
