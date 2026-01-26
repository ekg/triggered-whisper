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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.example.whispertoinput.recorder.RecorderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Accessibility service for system navigation with controller.
 * Handles R1 + D-pad combinations for Home, Back, and Recent Apps.
 * Handles L1 for voice input via Gboard or Whisper backends.
 */
class NavigationAccessibilityService : AccessibilityService() {

    // Track R1 modifier key state
    private var isR1ModPressed: Boolean = false

    // Whisper recording state
    private var recorderManager: RecorderManager? = null
    private var whisperTranscriber: WhisperTranscriber? = null
    private var isRecording: Boolean = false
    private var currentAudioFile: String? = null

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

        // Initialize Whisper components
        recorderManager = RecorderManager(this)
        whisperTranscriber = WhisperTranscriber()
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
        // Check which backend is configured
        CoroutineScope(Dispatchers.Main).launch {
            val backend = dataStore.data.map { preferences ->
                preferences[SPEECH_TO_TEXT_BACKEND] ?: getString(R.string.settings_option_gboard_voice)
            }.first()

            Log.d(TAG, "Voice input backend: $backend")

            if (backend == getString(R.string.settings_option_gboard_voice)) {
                triggerGboardVoiceInput()
            } else {
                // Whisper backend - toggle recording
                toggleWhisperRecording(backend)
            }
        }
    }

    private fun triggerGboardVoiceInput() {
        Log.d(TAG, "Attempting to trigger Gboard voice input")

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

    private fun toggleWhisperRecording(backend: String) {
        if (isRecording) {
            // Stop recording and transcribe
            stopRecordingAndTranscribe(backend)
        } else {
            // Start recording
            startRecording(backend)
        }
    }

    private fun startRecording(backend: String) {
        val recorder = recorderManager ?: return

        // Check permissions
        if (!recorder.allPermissionsGranted(this)) {
            Log.e(TAG, "Microphone permission not granted")
            showToast("Microphone permission required")
            return
        }

        // Determine audio format based on backend
        val useOggFormat = backend == getString(R.string.settings_option_nvidia_nim)
        val extension = if (useOggFormat) "ogg" else "m4a"

        // Create audio file in cache directory
        val audioFile = File(cacheDir, "whisper_audio.$extension")
        currentAudioFile = audioFile.absolutePath

        try {
            recorder.start(this, currentAudioFile!!, useOggFormat)
            isRecording = true
            Log.d(TAG, "Started recording to $currentAudioFile")
            showToast("Recording...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            showToast("Failed to start recording")
        }
    }

    private fun stopRecordingAndTranscribe(backend: String) {
        val recorder = recorderManager ?: return
        val transcriber = whisperTranscriber ?: return
        val audioFile = currentAudioFile ?: return

        try {
            recorder.stop()
            isRecording = false
            Log.d(TAG, "Stopped recording, starting transcription")
            showToast("Transcribing...")

            // Determine media type based on file extension
            val mediaType = if (audioFile.endsWith(".ogg")) "audio/ogg" else "audio/mp4"

            transcriber.startAsync(
                context = this,
                filename = audioFile,
                mediaType = mediaType,
                attachToEnd = "",
                callback = { transcribedText ->
                    if (transcribedText != null) {
                        Log.d(TAG, "Transcription result: $transcribedText")
                        injectText(transcribedText)
                    } else {
                        Log.d(TAG, "Transcription returned null")
                    }
                },
                exceptionCallback = { errorMessage ->
                    Log.e(TAG, "Transcription error: $errorMessage")
                    showToast("Error: $errorMessage")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            isRecording = false
            showToast("Failed to transcribe")
        }
    }

    /**
     * Inject transcribed text into the currently focused input field.
     */
    private fun injectText(text: String) {
        Log.d(TAG, "Injecting text: $text")

        // Try to find the focused input field and set its text
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                // Try ACTION_SET_TEXT first (works for editable fields)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val args = Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (result) {
                        Log.d(TAG, "Injected text via ACTION_SET_TEXT")
                        showToast("Done")
                        return
                    }
                }

                // Try pasting via clipboard
                Log.d(TAG, "ACTION_SET_TEXT failed, trying clipboard paste")
            }
        }

        // Fallback: use clipboard and paste
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("transcription", text)
            clipboard.setPrimaryClip(clip)

            // Try to paste using accessibility action
            val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                val pasteResult = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (pasteResult) {
                    Log.d(TAG, "Injected text via clipboard paste")
                    showToast("Done")
                    return
                }
            }

            // Last resort: tell user text is in clipboard
            Log.d(TAG, "Could not auto-paste, text is in clipboard")
            showToast("Text copied to clipboard")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject text", e)
            showToast("Failed to insert text")
        }
    }

    private fun showToast(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(this@NavigationAccessibilityService, message, Toast.LENGTH_SHORT).show()
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
