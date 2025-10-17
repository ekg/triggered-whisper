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

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service for system navigation with controller.
 * Handles R1 + D-pad combinations for Home, Back, and Recent Apps.
 * Does NOT handle text input or voice - that's still handled by WhisperInputService.
 */
class NavigationAccessibilityService : AccessibilityService() {

    // Track R1 modifier key state
    private var isR1ModPressed: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "NavigationAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle accessibility events for navigation
        // We only care about hardware key events
    }

    override fun onInterrupt() {
        Log.d(TAG, "NavigationAccessibilityService interrupted")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        val keyCode = event.keyCode
        val action = event.action

        Log.d(TAG, "onKeyEvent: keyCode=$keyCode action=$action")

        when (action) {
            KeyEvent.ACTION_DOWN -> {
                return handleKeyDown(keyCode)
            }
            KeyEvent.ACTION_UP -> {
                return handleKeyUp(keyCode)
            }
        }

        return false
    }

    private fun handleKeyDown(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                // R1 pressed - enter modifier mode
                isR1ModPressed = true
                Log.d(TAG, "R1 modifier pressed")
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isR1ModPressed) {
                    // R1+Up: Home
                    Log.d(TAG, "R1+Up: Going Home")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isR1ModPressed) {
                    // R1+Down: Recent Apps
                    Log.d(TAG, "R1+Down: Opening Recent Apps")
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isR1ModPressed) {
                    // R1+Left: Back
                    Log.d(TAG, "R1+Left: Going Back")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return true
                }
            }
        }

        // Let other key events pass through to other apps/services
        return false
    }

    private fun handleKeyUp(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                // R1 released - exit modifier mode
                isR1ModPressed = false
                Log.d(TAG, "R1 modifier released")
                return true
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NavigationAccessibilityService destroyed")
    }

    companion object {
        private const val TAG = "NavAccessibility"
    }
}
