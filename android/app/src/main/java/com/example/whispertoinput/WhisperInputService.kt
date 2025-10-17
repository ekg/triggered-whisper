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

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.github.liuyueyi.quick.transfer.constants.TransType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val RECORDED_AUDIO_FILENAME_M4A = "recorded.m4a"
private const val RECORDED_AUDIO_FILENAME_OGG = "recorded.ogg"
private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val AUDIO_MEDIA_TYPE_OGG = "audio/ogg"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager? = null
    private var recordedAudioFilename: String = ""
    private var audioMediaType: String = AUDIO_MEDIA_TYPE_M4A
    private var useOggFormat: Boolean = false
    private var isFirstTime: Boolean = true

    // Track button states for modifier key detection
    private var isR1ModPressed: Boolean = false

    // Floating keyboard window
    private var floatingWindow: FloatingKeyboardWindow? = null
    private var useFloatingKeyboard: Boolean = false
    private var isCurrentlyFloating: Boolean = false

    // Recording time tracking for WPM calculation
    private var recordingStartTime: Long = 0

    private fun transcriptionCallback(text: String?) {
        if (!text.isNullOrEmpty()) {
            currentInputConnection?.commitText(text, 1)

            // Calculate WPM (words per minute)
            val recordingDurationMs = System.currentTimeMillis() - recordingStartTime
            val recordingDurationMin = recordingDurationMs / 60000.0
            val wordCount = text.trim().split("\\s+".toRegex()).size
            val wpm = if (recordingDurationMin > 0) {
                (wordCount / recordingDurationMin).toInt()
            } else {
                0
            }

            // Display WPM and word count
            whisperKeyboard.displayWPM(wpm, wordCount, recordingDurationMs)

            // Check if auto-switch-back is enabled and switch if so
            CoroutineScope(Dispatchers.Main).launch {
                val autoSwitchBack = dataStore.data.map { preferences: Preferences ->
                    preferences[AUTO_SWITCH_BACK] ?: false
                }.first()
                if (autoSwitchBack) {
                    onSwitchIme()
                }
            }
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        whisperKeyboard.reset()
    }

    private suspend fun updateAudioFormat() {
        val backend = dataStore.data.map { preferences: Preferences ->
            preferences[SPEECH_TO_TEXT_BACKEND] ?: getString(R.string.settings_option_openai_api)
        }.first()
        
        useOggFormat = backend == getString(R.string.settings_option_nvidia_nim)
        if (useOggFormat) {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_OGG}"
            audioMediaType = AUDIO_MEDIA_TYPE_OGG
        } else {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_M4A}"
            audioMediaType = AUDIO_MEDIA_TYPE_M4A
        }
    }

    override fun onCreateInputView(): View {
        // Initialize members with regard to this context
        recorderManager = RecorderManager(this)

        // Preload conversion table
        ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TAIWAN)
        ChineseUtils.preLoad(true, TransType.TAIWAN_TO_SIMPLE)

        // Initialize audio format based on backend setting
        CoroutineScope(Dispatchers.Main).launch {
            updateAudioFormat()
        }

        // Should offer ime switch?
        val shouldOfferImeSwitch: Boolean =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                shouldOfferSwitchingToNextInputMethod()
            } else {
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                inputMethodManager.shouldOfferSwitchingToNextInputMethod(token)
            }

        // Sets up recorder manager
        recorderManager!!.setOnUpdateMicrophoneAmplitude { amplitude ->
            onUpdateMicrophoneAmplitude(amplitude)
        }

        // Returns the keyboard after setting it up and inflating its layout
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return whisperKeyboard.setup(layoutInflater,
            shouldOfferImeSwitch,
            isLandscape,
            { onStartRecording() },
            { onCancelRecording() },
            { attachToEnd -> onStartTranscription(attachToEnd) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSpaceBar() },
            { onSwitchIme() },
            { onOpenSettings() },
            { shouldShowRetry() },
            { char -> sendControlChar(char) },
            { keyCode -> sendSystemKey(keyCode) },
            { char -> sendTmuxSequence(char) },
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Check floating mode setting to determine if we should apply size reduction
        CoroutineScope(Dispatchers.Main).launch {
            val willUseFloating = isLandscape && dataStore.data.map { preferences: Preferences ->
                preferences[FLOATING_KEYBOARD_LANDSCAPE] ?: false
            }.first()

            // If using floating mode, don't apply reduction (keep full portrait size)
            // If in landscape without floating, apply 25% reduction
            whisperKeyboard.updateOrientation(isLandscape, applyReduction = !willUseFloating)

            // Handle floating window
            updateFloatingWindow(isLandscape)
        }
    }

    private suspend fun updateFloatingWindow(isLandscape: Boolean) {
        // Check if floating keyboard setting is enabled
        useFloatingKeyboard = dataStore.data.map { preferences: Preferences ->
            preferences[FLOATING_KEYBOARD_LANDSCAPE] ?: false
        }.first()

        Log.d("whisper-input", "updateFloatingWindow: isLandscape=$isLandscape, useFloatingKeyboard=$useFloatingKeyboard")

        if (isLandscape && useFloatingKeyboard) {
            // Check if we have overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val hasPermission = android.provider.Settings.canDrawOverlays(this)
                Log.d("whisper-input", "Overlay permission check: hasPermission=$hasPermission")
                if (!hasPermission) {
                    // No permission - show toast and open app permissions page
                    Toast.makeText(this, "Opening Triggered Whisper permissions. Enable 'Display over other apps'", Toast.LENGTH_LONG).show()

                    // Open app permissions page
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = android.net.Uri.parse("package:$packageName")
                        intent.addCategory(Intent.CATEGORY_DEFAULT)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("whisper-input", "Failed to open app permissions settings", e)
                        Toast.makeText(this, "Please enable 'Display over other apps' in Android Settings > Apps > Triggered Whisper", Toast.LENGTH_LONG).show()
                    }
                    return
                }
            }

            // Show floating window
            Log.d("whisper-input", "Attempting to show floating window...")
            if (floatingWindow == null) {
                Log.d("whisper-input", "Creating new FloatingKeyboardWindow")
                floatingWindow = FloatingKeyboardWindow(this, whisperKeyboard)
            }
            if (!floatingWindow!!.isShowing()) {
                Log.d("whisper-input", "Calling floatingWindow.show()")
                whisperKeyboard.lockDimensions()  // Lock dimensions before showing
                floatingWindow!!.show()
                isCurrentlyFloating = true
            } else {
                Log.d("whisper-input", "Floating window already showing")
            }
        } else {
            Log.d("whisper-input", "Hiding floating window (if any)")
            // Hide floating window
            floatingWindow?.hide()
            whisperKeyboard.unlockDimensions()  // Unlock dimensions when hiding
            isCurrentlyFloating = false
        }
    }

    private fun onStartRecording() {
        // Upon starting recording, check whether audio permission is granted.
        if (!recorderManager!!.allPermissionsGranted(this)) {
            // If not, launch app MainActivity (for permission setup).
            launchMainActivity()
            whisperKeyboard.reset()
            return
        }

        // Track recording start time for WPM calculation
        recordingStartTime = System.currentTimeMillis()

        recorderManager!!.start(this, recordedAudioFilename, useOggFormat)
    }

    // when mic amplitude is updated, notify the keyboard
    // this callback is registered to the recorder manager
    private fun onUpdateMicrophoneAmplitude(amplitude: Int) {
        whisperKeyboard.updateMicrophoneAmplitude(amplitude)
    }

    private fun onCancelRecording() {
        recorderManager!!.stop()
    }

    private fun onStartTranscription(attachToEnd: String) {
        recorderManager!!.stop()
        whisperTranscriber.startAsync(this,
            recordedAudioFilename,
            audioMediaType,
            attachToEnd,
            { transcriptionCallback(it) },
            { transcriptionExceptionCallback(it) })
    }

    private fun onCancelTranscription() {
        whisperTranscriber.stop()
    }

    private fun onDeleteText() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)

        // Deletes cursor pointed text, or all selected texts
        if (TextUtils.isEmpty(selectedText)) {
            inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    private fun onSwitchIme() {
        // Before API Level 28, switchToPreviousInputMethod() was not available
        if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val token: IBinder? = window?.window?.attributes?.token
            inputMethodManager.switchToLastInputMethod(token)
        }

    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    private fun onEnter() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    private fun onSpaceBar() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(" ", 1)
    }

    private fun shouldShowRetry(): Boolean {
        val exists = File(recordedAudioFilename).exists()
        return exists
    }

    // Opens up app MainActivity
    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()

        // If this is the first time calling onWindowShown, it means this IME is just being switched to.
        // Automatically starts recording after switching to Whisper Input. (if settings enabled)
        // Dispatch a coroutine to do this task.
        CoroutineScope(Dispatchers.Main).launch {
            // Update audio format based on current backend setting
            updateAudioFormat()

            // Check if we should show floating window and update orientation accordingly
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val willUseFloating = isLandscape && dataStore.data.map { preferences: Preferences ->
                preferences[FLOATING_KEYBOARD_LANDSCAPE] ?: false
            }.first()

            // Update orientation with correct reduction setting
            whisperKeyboard.updateOrientation(isLandscape, applyReduction = !willUseFloating)

            updateFloatingWindow(isLandscape)

            // Check if hotkey bar should be shown
            val showHotkeyBar = dataStore.data.map { preferences: Preferences ->
                preferences[SHOW_HOTKEY_BAR] ?: false
            }.first()
            whisperKeyboard.setHotkeyBarVisibility(showHotkeyBar)

            if (!isFirstTime) return@launch
            isFirstTime = false
            val isAutoStartRecording = dataStore.data.map { preferences: Preferences ->
                preferences[AUTO_RECORDING_START] ?: true
            }.first()
            if (isAutoStartRecording) {
                whisperKeyboard.tryStartRecording()
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
        floatingWindow?.hide()
        whisperKeyboard.unlockDimensions()
        isCurrentlyFloating = false
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
        floatingWindow?.hide()
        whisperKeyboard.unlockDimensions()
        floatingWindow = null
        isCurrentlyFloating = false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Get a human-readable name for the key
        val keyName = KeyEvent.keyCodeToString(keyCode)

        Log.d("whisper-input", "onKeyDown: keyCode=$keyCode ($keyName)")

        // Display ALL key events in debug panel
        whisperKeyboard.displayKeyEvent(keyCode, keyName)

        // Map controller buttons with new modifier scheme
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (isR1ModPressed) {
                    // R1+L1: New tmux pane (horizontal split - Ctrl+Q ")
                    Log.d("whisper-input", "R1+L1 pressed, creating new tmux pane")
                    whisperKeyboard.displayKeyEvent(keyCode, "➕ NEW PANE")
                    sendTmuxSequence('"')
                } else {
                    // L1 alone: Toggle recording (listen)
                    Log.d("whisper-input", "L1 pressed, toggling recording")
                    whisperKeyboard.toggleRecording()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                // R1: Modifier key
                isR1ModPressed = true
                Log.d("whisper-input", "R1 mod key pressed")
                whisperKeyboard.displayKeyEvent(keyCode, "🔧 MOD")
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (isR1ModPressed) {
                    // R1+A: Send just Ctrl+Q (tmux command mode, then user can press arrows)
                    Log.d("whisper-input", "R1+A pressed, sending Ctrl+Q")
                    whisperKeyboard.displayKeyEvent(keyCode, "⌨️ CTRL+Q")
                    sendControlChar('q')
                } else {
                    // A alone: Ctrl+R (fzf autocomplete)
                    Log.d("whisper-input", "A pressed, sending Ctrl+R")
                    whisperKeyboard.displayKeyEvent(keyCode, "🔍 FZF")
                    sendControlChar('r')
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (isR1ModPressed) {
                    // R1+X: Send Ctrl+C (cancel/interrupt)
                    Log.d("whisper-input", "R1+X pressed, sending Ctrl+C")
                    whisperKeyboard.displayKeyEvent(keyCode, "❌ CTRL+C")
                    sendControlChar('c')
                } else {
                    // X alone: Delete
                    Log.d("whisper-input", "X pressed, delete")
                    onDeleteText()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (isR1ModPressed) {
                    // R1+Y: Send Ctrl+D (exit/logout)
                    Log.d("whisper-input", "R1+Y pressed, sending Ctrl+D")
                    whisperKeyboard.displayKeyEvent(keyCode, "🚪 CTRL+D")
                    sendControlChar('d')
                } else {
                    // Y alone: Space
                    Log.d("whisper-input", "Y pressed, space")
                    onSpaceBar()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                // Button B: Trigger enter (stop recording with newline, or send enter)
                Log.d("whisper-input", "Button B pressed, triggering enter")
                whisperKeyboard.triggerEnter()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (isR1ModPressed) {
                    // R1+L2: Send Ctrl+Q C (tmux new window)
                    Log.d("whisper-input", "R1+L2 pressed, creating new tmux window")
                    whisperKeyboard.displayKeyEvent(keyCode, "🪟 NEW WIN")
                    sendTmuxSequence('c')
                } else {
                    // L2: Send Ctrl+Q P (tmux previous window)
                    Log.d("whisper-input", "L2 pressed, sending Ctrl+Q P")
                    whisperKeyboard.displayKeyEvent(keyCode, "◀️ PREV")
                    sendTmuxSequence('p')
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                // R2: Send Ctrl+Q N (tmux next window)
                Log.d("whisper-input", "R2 pressed, sending Ctrl+Q N")
                whisperKeyboard.displayKeyEvent(keyCode, "▶️ NEXT")
                sendTmuxSequence('n')
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isR1ModPressed) {
                    // R1+Up: Home button
                    Log.d("whisper-input", "R1+Up pressed, sending Home")
                    whisperKeyboard.displayKeyEvent(keyCode, "🏠 HOME")
                    sendSystemKey(KeyEvent.KEYCODE_HOME)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isR1ModPressed) {
                    // R1+Down: Recent apps / Task switcher
                    Log.d("whisper-input", "R1+Down pressed, sending Recent Apps")
                    whisperKeyboard.displayKeyEvent(keyCode, "📱 RECENT")
                    sendSystemKey(KeyEvent.KEYCODE_APP_SWITCH)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isR1ModPressed) {
                    // R1+Left: Back button
                    Log.d("whisper-input", "R1+Left pressed, sending Back")
                    whisperKeyboard.displayKeyEvent(keyCode, "⬅️ BACK")
                    sendSystemKey(KeyEvent.KEYCODE_BACK)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Track button releases for modifier key
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                isR1ModPressed = false
                Log.d("whisper-input", "R1 mod key released")
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun sendControlChar(char: Char) {
        val inputConnection = currentInputConnection ?: return

        // Send control character (Ctrl+char)
        // Control characters are ASCII values 1-26 for Ctrl+A through Ctrl+Z
        // Formula: ASCII value = (char.uppercaseChar() - 'A' + 1)
        val controlCode = (char.uppercaseChar() - 'A' + 1).toChar()
        inputConnection.commitText(controlCode.toString(), 1)
    }

    private fun sendTmuxSequence(finalChar: Char) {
        val inputConnection = currentInputConnection ?: return

        // Send Ctrl+Q as actual control character (ASCII 17), then the command letter
        // Ctrl+Q = 0x11 (17 in decimal)
        val ctrlQ = "\u0011"  // Ctrl+Q control character

        // Send Ctrl+Q followed by the letter (p=previous, n=next, c=create, ")
        inputConnection.commitText(ctrlQ + finalChar, 1)
    }

    private fun sendSystemKey(keyCode: Int) {
        // Send system key events (Home, Back, Recent apps, etc.)
        val inputConnection = currentInputConnection ?: return
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
