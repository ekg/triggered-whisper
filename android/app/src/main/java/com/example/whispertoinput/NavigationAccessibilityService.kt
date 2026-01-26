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

        // Strategy 2: Tap at common mic button location in keyboard area
        if (tapKeyboardMicArea()) {
            Log.d(TAG, "Tapped keyboard mic area")
            return
        }

        // Strategy 3: Fall back to launching voice recognition activity
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
     * Search the accessibility tree for a microphone/voice button and click it.
     */
    private fun findAndClickMicButton(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        // Common content descriptions for mic buttons in various keyboards
        val micDescriptions = listOf(
            "voice", "Voice", "microphone", "Microphone", "mic", "Mic",
            "Voice typing", "voice typing", "Voice input", "voice input",
            "Speak", "speak", "dictate", "Dictate"
        )

        for (desc in micDescriptions) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(desc)
            for (node in nodes) {
                val nodeDesc = node.contentDescription?.toString() ?: ""
                val nodeText = node.text?.toString() ?: ""
                Log.d(TAG, "Found node with desc='$nodeDesc' text='$nodeText' class=${node.className}")

                // Check if this looks like a mic button
                if (nodeDesc.contains("voice", ignoreCase = true) ||
                    nodeDesc.contains("mic", ignoreCase = true) ||
                    nodeDesc.contains("speak", ignoreCase = true) ||
                    nodeDesc.contains("dictate", ignoreCase = true)) {

                    if (node.isClickable) {
                        Log.d(TAG, "Clicking mic button: $nodeDesc")
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    } else {
                        // Try clicking the parent
                        val parent = node.parent
                        if (parent?.isClickable == true) {
                            Log.d(TAG, "Clicking mic button parent: ${parent.contentDescription}")
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            return true
                        }

                        // Try tapping at the node's location
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.width() > 0 && rect.height() > 0) {
                            Log.d(TAG, "Tapping mic button location: ${rect.centerX()}, ${rect.centerY()}")
                            return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                        }
                    }
                }
            }
        }

        return false
    }

    /**
     * Tap at the typical location of the mic button in Gboard's toolbar.
     * Gboard usually has a toolbar above the main keys with mic on the right side.
     */
    private fun tapKeyboardMicArea(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        // Try to find the keyboard window/view
        val keyboardNode = findKeyboardNode(rootNode)
        if (keyboardNode != null) {
            val rect = Rect()
            keyboardNode.getBoundsInScreen(rect)
            Log.d(TAG, "Found keyboard bounds: $rect")

            // Gboard's mic is typically in the toolbar area (top of keyboard, right side)
            // The toolbar is roughly the top 15% of the keyboard, mic is on the right third
            val micX = rect.right - (rect.width() * 0.1f)  // 10% from right edge
            val micY = rect.top + (rect.height() * 0.08f)  // 8% from top (in toolbar)

            Log.d(TAG, "Tapping estimated mic location: $micX, $micY")
            return tapAt(micX, micY)
        }

        // If we can't find keyboard, try tapping at screen bottom-right area
        // This is a last resort based on typical keyboard layouts
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        // Estimate: keyboard takes bottom ~40% of screen, mic in top-right of that
        val micX = screenWidth * 0.9f  // 90% from left
        val micY = screenHeight * 0.65f  // 65% from top (top of keyboard area)

        Log.d(TAG, "Tapping estimated screen mic location: $micX, $micY")
        return tapAt(micX, micY)
    }

    /**
     * Find the keyboard's root node in the accessibility tree.
     */
    private fun findKeyboardNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Look for nodes from known keyboard packages
        val keyboardPackages = listOf(
            "com.google.android.inputmethod.latin",  // Gboard
            "com.samsung.android.honeyboard",        // Samsung keyboard
            "com.swiftkey.swiftkey",                 // SwiftKey
            "com.touchtype.swiftkey"                 // SwiftKey alternative
        )

        return findNodeByPackage(root, keyboardPackages)
    }

    private fun findNodeByPackage(node: AccessibilityNodeInfo, packages: List<String>): AccessibilityNodeInfo? {
        val pkg = node.packageName?.toString() ?: ""
        if (packages.any { pkg.contains(it, ignoreCase = true) }) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByPackage(child, packages)
            if (result != null) return result
        }

        return null
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
