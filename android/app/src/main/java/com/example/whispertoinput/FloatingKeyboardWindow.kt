/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2024 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.whispertoinput.keyboard.WhisperKeyboard

class FloatingKeyboardWindow(
    private val context: Context,
    private val whisperKeyboard: WhisperKeyboard
) {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    // For tracking dragging
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging: Boolean = false

    // Store portrait keyboard width
    private var keyboardWidth: Int = 0

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (floatingView != null) {
            android.util.Log.d("whisper-input", "FloatingKeyboardWindow.show(): Already showing, returning")
            return // Already showing
        }

        android.util.Log.d("whisper-input", "FloatingKeyboardWindow.show(): Starting...")

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Get screen dimensions
        val displayMetrics = DisplayMetrics()
        windowManager!!.defaultDisplay.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels

        // Calculate keyboard width (use portrait width, which would be screen height in landscape)
        keyboardWidth = minOf(screenWidth, displayMetrics.heightPixels)

        // Create layout params for overlay window
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            keyboardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - keyboardWidth) / 2 // Center horizontally initially
            y = displayMetrics.heightPixels - 200 // Near bottom
        }

        // Create container view
        val containerView = FrameLayout(context)

        // Get keyboard view from WhisperKeyboard
        floatingView = whisperKeyboard.getKeyboardView()

        if (floatingView?.parent != null) {
            // Remove from previous parent if exists
            (floatingView?.parent as? android.view.ViewGroup)?.removeView(floatingView)
        }

        containerView.addView(floatingView)

        // Set up touch listener for dragging
        containerView.setOnTouchListener { view, event ->
            handleTouch(view, event)
        }

        // Add to window manager
        android.util.Log.d("whisper-input", "FloatingKeyboardWindow.show(): Adding view to window manager with width=$keyboardWidth")
        try {
            windowManager!!.addView(containerView, params)
            android.util.Log.d("whisper-input", "FloatingKeyboardWindow.show(): Successfully added to window manager")
        } catch (e: Exception) {
            android.util.Log.e("whisper-input", "FloatingKeyboardWindow.show(): Failed to add view to window manager", e)
            throw e
        }

        // Store the container as our floating view for later removal
        floatingView = containerView
        android.util.Log.d("whisper-input", "FloatingKeyboardWindow.show(): Complete!")
    }

    private fun isTouchOnButton(view: View, x: Float, y: Float): Boolean {
        // Check if touch is on any clickable child views (buttons)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.isClickable && child.visibility == View.VISIBLE) {
                    val location = IntArray(2)
                    child.getLocationOnScreen(location)
                    val left = location[0]
                    val top = location[1]
                    val right = left + child.width
                    val bottom = top + child.height

                    if (x >= left && x <= right && y >= top && y <= bottom) {
                        return true
                    }
                }
                // Recursively check child view groups
                if (child is android.view.ViewGroup && isTouchOnButton(child, x, y)) {
                    return true
                }
            }
        }
        return false
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check if touching a button
                if (isTouchOnButton(view, event.rawX, event.rawY)) {
                    // Let the button handle the touch
                    return false
                }

                // Start drag on non-button area
                initialX = params!!.x
                initialY = params!!.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY

                // If moved more than a threshold, start dragging
                if (!isDragging && (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10)) {
                    isDragging = true
                }

                if (isDragging) {
                    params!!.x = initialX + deltaX.toInt()
                    params!!.y = initialY + deltaY.toInt()
                    windowManager!!.updateViewLayout(view, params)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    return true
                }
                return false
            }
        }
        return false
    }

    fun hide() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager!!.removeView(floatingView)
            } catch (e: Exception) {
                // View might already be removed
            }
            floatingView = null
        }
    }

    fun isShowing(): Boolean {
        return floatingView != null
    }
}
