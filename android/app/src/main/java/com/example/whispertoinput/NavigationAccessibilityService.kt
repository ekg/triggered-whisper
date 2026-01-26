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
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Accessibility service for system navigation with controller.
 * Handles R1 + D-pad combinations for Home, Back, and Recent Apps.
 * Also handles switching back to our IME after voice input.
 */
class NavigationAccessibilityService : AccessibilityService() {

    // Track R1 modifier key state
    private var isR1ModPressed: Boolean = false

    companion object {
        private const val TAG = "NavAccessibility"
        const val OUR_IME_ID = "com.example.whispertoinput.controller/com.example.whispertoinput.WhisperInputService"

        // Flag to track if we triggered voice input and should switch back
        @Volatile
        var shouldSwitchBackToOurIme: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "NavigationAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Watch for window changes that might indicate voice input is done
        if (shouldSwitchBackToOurIme && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: ""
            Log.d(TAG, "Window state changed: $packageName")

            // If we see a window change that's not voice-related, switch back
            // Voice input packages: com.google.android.googlequicksearchbox, com.google.android.tts
            if (!packageName.contains("voice", ignoreCase = true) &&
                !packageName.contains("speech", ignoreCase = true) &&
                !packageName.contains("googlequicksearchbox", ignoreCase = true)) {

                Log.d(TAG, "Voice input seems done, switching back to our IME")
                switchToOurIme()
                shouldSwitchBackToOurIme = false
            }
        }
    }

    private fun triggerVoiceInput() {
        try {
            // Launch Google voice recognition as an activity
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "Launched voice recognition activity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch voice input", e)
            // Try alternative: trigger voice assist
            try {
                val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to launch voice command", e2)
            }
        }
    }

    private fun switchToOurIme() {
        try {
            // Try to switch IME using shell command (works on some devices)
            Log.d(TAG, "Attempting to switch to IME: $OUR_IME_ID")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ime set $OUR_IME_ID"))
            val exitCode = process.waitFor()
            Log.d(TAG, "ime set command exit code: $exitCode")

            if (exitCode != 0) {
                // Command failed, try alternative approach - show IME picker
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch IME", e)
            // Fallback: show IME picker
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to show IME picker", e2)
            }
        }
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
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (isR1ModPressed) {
                    // R1+L1 is handled elsewhere (tmux new pane)
                    return false
                }
                // L1 alone: Trigger voice input
                Log.d(TAG, "L1 pressed - triggering voice input")
                triggerVoiceInput()
                return true
            }
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
}
