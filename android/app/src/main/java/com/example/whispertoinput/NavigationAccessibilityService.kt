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
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
        Log.d(TAG, "Attempting to trigger voice input via gesture")

        // Strategy 1: Find and click the microphone button in the keyboard
        if (findAndClickMicButton()) {
            Log.d(TAG, "Successfully found and clicked mic button")
            return
        }

        // Strategy 2: Fall back to launching voice recognition activity
        Log.d(TAG, "Falling back to voice recognition intent")
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "Launched voice recognition activity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch voice input", e)
        }
    }

    /**
     * Search ALL windows (including IME) for a microphone/voice button and click it.
     * Uses getWindows() to access the keyboard window which is separate from the app window.
     */
    private fun findAndClickMicButton(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false
        }

        // Get all windows - this includes the IME (keyboard) window
        val allWindows = windows
        Log.d(TAG, "Found ${allWindows.size} windows")

        for (window in allWindows) {
            val windowRoot = window.root ?: continue
            val windowPkg = windowRoot.packageName?.toString() ?: ""
            Log.d(TAG, "Window: type=${window.type}, pkg=$windowPkg")

            // Look specifically in the INPUT_METHOD window (type 2)
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                Log.d(TAG, "Found INPUT_METHOD window from $windowPkg")
                if (searchAndClickMicInNode(windowRoot)) {
                    return true
                }
            }
        }

        // Also try the main window in case keyboard is embedded
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            if (searchAndClickMicInNode(rootNode)) {
                return true
            }
        }

        return false
    }

    /**
     * Recursively search a node tree for mic/voice buttons and click them.
     */
    private fun searchAndClickMicInNode(root: AccessibilityNodeInfo): Boolean {
        // Look specifically for Gboard's voice typing toggle buttons
        // These have specific content descriptions we can match exactly
        val exactMatches = listOf(
            "Use voice typing",      // Start voice typing
            "Stop voice typing",     // Stop voice typing (toggle off)
            "Voice typing"           // Generic voice typing button
        )

        // First pass: look for exact matches (preferred)
        for (exactDesc in exactMatches) {
            val nodes = root.findAccessibilityNodeInfosByText(exactDesc)
            for (node in nodes) {
                val nodeDesc = node.contentDescription?.toString() ?: ""
                Log.d(TAG, "Checking node: desc='$nodeDesc' clickable=${node.isClickable}")

                // Check for exact or close match (case-insensitive)
                if (nodeDesc.equals(exactDesc, ignoreCase = true) ||
                    nodeDesc.startsWith(exactDesc, ignoreCase = true)) {

                    if (node.isClickable) {
                        Log.d(TAG, "Clicking voice typing button: $nodeDesc")
                        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "Click result: $result")
                        if (result) return true
                    }
                }
            }
        }

        // Second pass: look for microphone buttons by class name (VoiceDictationButton)
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, allNodes)

        for (node in allNodes) {
            val nodeClass = node.className?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""

            if (nodeClass.contains("VoiceDictation", ignoreCase = true) ||
                nodeClass.contains("VoiceButton", ignoreCase = true)) {
                Log.d(TAG, "Found voice button by class: $nodeClass desc='$nodeDesc'")

                if (node.isClickable) {
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Click result: $result")
                    if (result) return true
                }
            }
        }

        return false
    }

    /**
     * Collect all nodes in the tree into a list.
     */
    private fun collectAllNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        list.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, list)
        }
    }


    /**
     * Perform a tap gesture at the specified screen coordinates.
     */
    private fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture requires API 24+")
            return false
        }

        val path = Path()
        path.moveTo(x, y)

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))

        return dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap gesture completed at $x, $y")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap gesture cancelled at $x, $y")
            }
        }, null)
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
