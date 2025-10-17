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

package com.example.whispertoinput.keyboard

import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.math.MathUtils
import com.example.whispertoinput.R
import kotlin.math.log10
import kotlin.math.pow

private const val AMPLITUDE_CLAMP_MIN: Int = 10
private const val AMPLITUDE_CLAMP_MAX: Int = 25000
private const val LOG_10_10: Float = 1.0F
private const val LOG_10_25000: Float = 4.398F
private const val AMPLITUDE_ANIMATION_DURATION: Long = 500
private val amplitudePowers: Array<Float> = arrayOf(0.5f, 1.0f, 2f, 3f)

class WhisperKeyboard {
    private enum class KeyboardStatus {
        Idle,             // Ready to start recording
        Recording,       // Currently recording
        Transcribing,    // Waiting for transcription results
    }

    // Keyboard event listeners. Assignable custom behaviors upon certain UI events (user-operated).
    private var onStartRecording: () -> Unit = { }
    private var onCancelRecording: () -> Unit = { }
    private var onStartTranscribing: (attachToEnd: String) -> Unit = { }
    private var onCancelTranscribing: () -> Unit = { }
    private var onButtonBackspace: () -> Unit = { }
    private var onSwitchIme: () -> Unit = { }
    private var onOpenSettings: () -> Unit = { }
    private var onEnter: () -> Unit = { }
    private var onSpaceBar: () -> Unit = { }
    private var shouldShowRetry: () -> Boolean = { false }
    private var onSendControlChar: (Char) -> Unit = { }
    private var onSendSystemKey: (Int) -> Unit = { }

    // Keyboard Status
    private var keyboardStatus: KeyboardStatus = KeyboardStatus.Idle

    // Lock dimensions to prevent changes (used in floating mode)
    private var dimensionsLocked: Boolean = false

    // Views & Keyboard Layout
    private var keyboardView: View? = null
    private var keyboardRow: ConstraintLayout? = null
    private var buttonMic: ImageButton? = null
    private var buttonMicFrame: View? = null
    private var buttonEnter: ImageButton? = null
    private var buttonCancel: ImageButton? = null
    private var buttonRetry: ImageButton? = null
    private var labelStatus: TextView? = null
    private var buttonSpaceBar: ImageButton? = null
    private var waitingIcon: ProgressBar? = null
    private var buttonBackspace: BackspaceButton? = null
    private var buttonPreviousIme: ImageButton? = null
    private var micRippleContainer: ConstraintLayout? = null
    private var micRipples: Array<ImageView> = emptyArray()
    private var debugKeyDisplay: TextView? = null
    private val debugKeyHistory = mutableListOf<String>()
    private var wpmText: String = "WPM: --"

    // Hotkey Bar
    private var hotkeyBar: View? = null

    fun setup(
        layoutInflater: LayoutInflater,
        shouldOfferImeSwitch: Boolean,
        isLandscape: Boolean,
        onStartRecording: () -> Unit,
        onCancelRecording: () -> Unit,
        onStartTranscribing: (attachToEnd: String) -> Unit,
        onCancelTranscribing: () -> Unit,
        onButtonBackspace: () -> Unit,
        onEnter: () -> Unit,
        onSpaceBar: () -> Unit,
        onSwitchIme: () -> Unit,
        onOpenSettings: () -> Unit,
        shouldShowRetry: () -> Boolean,
        onSendControlChar: (Char) -> Unit,
        onSendSystemKey: (Int) -> Unit,
    ): View {
        // Inflate the keyboard layout (now a LinearLayout with hotkey bar + main keyboard)
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardRow = keyboardView!!.findViewById(R.id.keyboard_view) as ConstraintLayout
        hotkeyBar = keyboardView!!.findViewById(R.id.hotkey_bar)

        buttonMicFrame = keyboardRow!!.findViewById(R.id.btn_mic_frame)
        buttonMic = keyboardRow!!.findViewById(R.id.btn_mic) as ImageButton
        buttonEnter = keyboardRow!!.findViewById(R.id.btn_enter) as ImageButton
        buttonCancel = keyboardRow!!.findViewById(R.id.btn_cancel) as ImageButton
        buttonRetry = keyboardRow!!.findViewById(R.id.btn_retry) as ImageButton
        labelStatus = keyboardRow!!.findViewById(R.id.label_status) as TextView
        buttonSpaceBar = keyboardRow!!.findViewById(R.id.btn_space_bar) as ImageButton
        waitingIcon = keyboardRow!!.findViewById(R.id.pb_waiting_icon) as ProgressBar
        buttonBackspace = keyboardRow!!.findViewById(R.id.btn_backspace) as BackspaceButton
        buttonPreviousIme = keyboardRow!!.findViewById(R.id.btn_previous_ime) as ImageButton
        micRippleContainer = keyboardRow!!.findViewById(R.id.mic_ripples) as ConstraintLayout
        micRipples = arrayOf(
            keyboardRow!!.findViewById(R.id.mic_ripple_0) as ImageView,
            keyboardRow!!.findViewById(R.id.mic_ripple_1) as ImageView,
            keyboardRow!!.findViewById(R.id.mic_ripple_2) as ImageView,
            keyboardRow!!.findViewById(R.id.mic_ripple_3) as ImageView
        )
        debugKeyDisplay = keyboardRow!!.findViewById(R.id.debug_key_display) as TextView

        // Hide buttonPreviousIme if necessary
        if (!shouldOfferImeSwitch) {
            buttonPreviousIme!!.visibility = View.GONE
        }

        // Set onClick listeners
        buttonMic!!.setOnClickListener { onButtonMicClick() }
        buttonEnter!!.setOnClickListener { onButtonEnterClick() }
        buttonCancel!!.setOnClickListener { onButtonCancelClick() }
        buttonRetry!!.setOnClickListener { onButtonRetryClick() }
        buttonBackspace!!.setBackspaceCallback { onButtonBackspaceClick() }
        buttonSpaceBar!!.setOnClickListener { onButtonSpaceBarClick() }

        if (shouldOfferImeSwitch) {
            buttonPreviousIme!!.setOnClickListener { onButtonPreviousImeClick() }
        }

        // Set event listeners
        this.onStartRecording = onStartRecording
        this.onCancelRecording = onCancelRecording
        this.onStartTranscribing = onStartTranscribing
        this.onCancelTranscribing = onCancelTranscribing
        this.onButtonBackspace = onButtonBackspace
        this.onSwitchIme = onSwitchIme
        this.onOpenSettings = onOpenSettings
        this.onEnter = onEnter
        this.onSpaceBar = onSpaceBar
        this.shouldShowRetry = shouldShowRetry
        this.onSendControlChar = onSendControlChar
        this.onSendSystemKey = onSendSystemKey

        // Setup hotkey bar buttons
        setupHotkeyBar()

        // Resets keyboard upon setup
        reset()

        // Don't apply initial orientation here - let onWindowShown() handle it
        // to ensure correct sizing with floating mode settings

        // Returns the keyboard view (non-nullable)
        return keyboardView!!
    }

    fun reset() {
        setKeyboardStatus(KeyboardStatus.Idle)
    }

    fun updateMicrophoneAmplitude(amplitude: Int) {
        if (keyboardStatus != KeyboardStatus.Recording) {
            return
        }

        val clampedAmplitude = MathUtils.clamp(
            amplitude,
            AMPLITUDE_CLAMP_MIN,
            AMPLITUDE_CLAMP_MAX
        )

        // decibel-like calculation
        val normalizedPower =
            (log10(clampedAmplitude * 1f) - LOG_10_10) / (LOG_10_25000 - LOG_10_10)

        // normalizedPower ranges from 0 to 1.
        // The inner-most ripple should be the most sensitive to audio,
        // represented by a gamma-correction-like curve.
        for (micRippleIdx in micRipples.indices) {
            micRipples[micRippleIdx].clearAnimation()
            micRipples[micRippleIdx].alpha = normalizedPower.pow(amplitudePowers[micRippleIdx])
            micRipples[micRippleIdx].animate().alpha(0f).setDuration(AMPLITUDE_ANIMATION_DURATION)
                .start()
        }
    }

    fun tryStartRecording() {
        if (keyboardStatus == KeyboardStatus.Idle) {
            setKeyboardStatus(KeyboardStatus.Recording)
            onStartRecording()
        }
    }

    fun tryCancelRecording() {
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelRecording()
        }
    }

    fun tryStartTranscribing(attachToEnd: String) {
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing(attachToEnd)
        }
    }

    fun toggleRecording() {
        // Mimics the mic button behavior: toggle between Idle and Recording states
        when (keyboardStatus) {
            KeyboardStatus.Idle -> {
                setKeyboardStatus(KeyboardStatus.Recording)
                onStartRecording()
            }
            KeyboardStatus.Recording -> {
                setKeyboardStatus(KeyboardStatus.Transcribing)
                onStartTranscribing("")
            }
            KeyboardStatus.Transcribing -> {
                // Do nothing to avoid double-clicking issues
                return
            }
        }
    }

    fun triggerEnter() {
        // Mimics the enter button behavior
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing("\r\n")
        } else {
            onEnter()
        }
    }

    private fun onButtonSpaceBarClick() {
        // Upon button space bar click.
        // Recording -> Start transcribing (with a whitespace included)
        // else -> invokes onSpaceBar
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing(" ")
        } else {
            onSpaceBar()
        }
    }

    private fun onButtonBackspaceClick() {
        // Currently, this onClick only makes a call to onButtonBackspace()
        this.onButtonBackspace()
    }

    private fun onButtonPreviousImeClick() {
        // Currently, this onClick only makes a call to onSwitchIme()
        this.onSwitchIme()
    }

    private fun onButtonSettingsClick() {
        // Currently, this onClick only makes a call to onOpenSettings()
        this.onOpenSettings()
    }

    private fun onButtonMicClick() {
        // Upon button mic click...
        // Idle -> Start Recording
        // Recording -> Finish Recording (without a newline)
        // Transcribing -> Nothing (to avoid double-clicking by mistake, which starts transcribing and then immediately cancels it)
        when (keyboardStatus) {
            KeyboardStatus.Idle -> {
                setKeyboardStatus(KeyboardStatus.Recording)
                onStartRecording()
            }

            KeyboardStatus.Recording -> {
                setKeyboardStatus(KeyboardStatus.Transcribing)
                onStartTranscribing("")
            }

            KeyboardStatus.Transcribing -> {
                return
            }
        }
    }

    private fun onButtonEnterClick() {
        // Upon button enter click.
        // Recording -> Start transcribing (with a newline included)
        // else -> invokes onEnter
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing("\r\n")
        } else {
            onEnter()
        }
    }

    private fun onButtonCancelClick() {
        // Upon button cancel click.
        // Recording -> Cancel
        // Transcribing -> Cancel
        // else -> nothing
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelRecording()
        } else if (keyboardStatus == KeyboardStatus.Transcribing) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelTranscribing()
        }
    }

    private fun onButtonRetryClick() {
        // Upon button retry click.
        // Idle -> Retry
        // else -> nothing
        if (keyboardStatus == KeyboardStatus.Idle) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing("")
        }
    }

    private fun setKeyboardStatus(newStatus: KeyboardStatus) {
        when (newStatus) {
            KeyboardStatus.Idle -> {
                labelStatus!!.setText(R.string.whisper_to_input)
                buttonMic!!.setImageResource(R.drawable.mic_idle)
                waitingIcon!!.visibility = View.INVISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                buttonCancel!!.isEnabled = false  // Disable but keep visible to maintain layout
                buttonRetry!!.visibility = if (shouldShowRetry()) View.VISIBLE else View.INVISIBLE
                micRippleContainer!!.visibility = View.GONE
                keyboardRow!!.keepScreenOn = false
            }

            KeyboardStatus.Recording -> {
                labelStatus!!.setText(R.string.recording)
                buttonMic!!.setImageResource(R.drawable.mic_pressed)
                waitingIcon!!.visibility = View.INVISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                buttonCancel!!.isEnabled = true
                buttonRetry!!.visibility = View.INVISIBLE
                micRippleContainer!!.visibility = View.VISIBLE
                keyboardRow!!.keepScreenOn = true
            }

            KeyboardStatus.Transcribing -> {
                labelStatus!!.setText(R.string.transcribing)
                buttonMic!!.setImageResource(R.drawable.mic_transcribing)
                waitingIcon!!.visibility = View.VISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                buttonCancel!!.isEnabled = true
                buttonRetry!!.visibility = View.INVISIBLE
                micRippleContainer!!.visibility = View.GONE
                keyboardRow!!.keepScreenOn = true
            }
        }

        keyboardStatus = newStatus
    }

    fun displayKeyEvent(keyCode: Int, keyName: String) {
        // Add to history (keep last 2 entries for compact display)
        val shortName = keyName.removePrefix("KEYCODE_")
        debugKeyHistory.add(0, "$keyCode: $shortName")
        if (debugKeyHistory.size > 2) {
            debugKeyHistory.removeAt(debugKeyHistory.size - 1)
        }

        // Update display with key events on top, WPM on bottom
        updateDebugDisplay()
    }

    fun displayWPM(wpm: Int, wordCount: Int, durationMs: Long) {
        // Format: "WPM: 120 (15w/7.5s)"
        val durationSec = durationMs / 1000.0
        wpmText = "WPM: $wpm (${wordCount}w/${String.format("%.1f", durationSec)}s)"
        updateDebugDisplay()
    }

    private fun updateDebugDisplay() {
        // Two line format:
        // Line 1: Key events (most recent first)
        // Line 2: WPM stats
        val keyText = debugKeyHistory.joinToString("\n").ifEmpty { "Keys..." }
        val displayText = "$keyText\n$wpmText"
        debugKeyDisplay?.text = displayText
    }

    fun lockDimensions() {
        dimensionsLocked = true
    }

    fun unlockDimensions() {
        dimensionsLocked = false
    }

    fun updateOrientation(isLandscape: Boolean, applyReduction: Boolean = true) {
        val keyboardRow = keyboardRow ?: return

        // Don't update dimensions if locked (prevents size changes in floating mode)
        if (dimensionsLocked) {
            return
        }

        // In landscape mode, reduce height by 25% by adjusting padding and button sizes
        // However, if applyReduction is false (e.g., when using floating mode), keep full portrait size
        val (padding, buttonSize, micFrameSize) = if (isLandscape && applyReduction) {
            // Landscape with reduction: 25% shorter (padding 1dp, buttons 24dp, mic frame 30dp)
            Triple(1, 24, 30)
        } else {
            // Portrait or landscape without reduction: normal size (padding 4dp, buttons 32dp, mic frame 40dp)
            Triple(4, 32, 40)
        }

        // Convert dp to pixels
        val density = keyboardRow.context.resources.displayMetrics.density
        val paddingPx = (padding * density).toInt()
        val buttonSizePx = (buttonSize * density).toInt()
        val micFrameSizePx = (micFrameSize * density).toInt()

        // Update keyboard padding
        keyboardRow.setPadding(0, paddingPx, 0, paddingPx)

        // Update button sizes
        updateButtonSize(buttonMic, buttonSizePx)
        updateButtonSize(buttonEnter, buttonSizePx)
        updateButtonSize(buttonCancel, buttonSizePx)
        updateButtonSize(buttonRetry, buttonSizePx)
        updateButtonSize(buttonBackspace, buttonSizePx)
        updateButtonSize(buttonSpaceBar, buttonSizePx)
        updateButtonSize(buttonPreviousIme, buttonSizePx)

        // Update mic frame size (it's slightly larger)
        updateButtonSize(buttonMicFrame, micFrameSizePx)

        // Request layout update
        keyboardRow.requestLayout()
    }

    private fun updateButtonSize(button: View?, sizePx: Int) {
        button?.layoutParams?.apply {
            width = sizePx
            height = sizePx
        }
    }

    fun getKeyboardView(): View? {
        return keyboardView
    }

    private fun setupHotkeyBar() {
        val hotkeyBar = hotkeyBar ?: return

        // Row 1: Basic Actions
        hotkeyBar.findViewById<Button>(R.id.btn_hk_listen)?.setOnClickListener { toggleRecording() }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_fzf)?.setOnClickListener { onSendControlChar('r') }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_enter)?.setOnClickListener { onEnter() }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_delete)?.setOnClickListener { onButtonBackspace() }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_space)?.setOnClickListener { onSpaceBar() }

        // Row 2: Tmux Operations
        hotkeyBar.findViewById<Button>(R.id.btn_hk_new_pane)?.setOnClickListener {
            // New pane: Ctrl+Q "
            onSendControlChar('q')
            // Note: We can't send the literal quote easily, the controller sends it
        }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_new_window)?.setOnClickListener {
            // New window: Ctrl+Q C
            onSendControlChar('q')
            // Note: Followed by 'c', the controller handles the full sequence
        }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_prev_window)?.setOnClickListener {
            // Prev window: Ctrl+Q p
            onSendControlChar('q')
        }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_next_window)?.setOnClickListener {
            // Next window: Ctrl+Q n
            onSendControlChar('q')
        }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_ctrl_q)?.setOnClickListener {
            onSendControlChar('q')
        }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_ctrl_c)?.setOnClickListener {
            onSendControlChar('c')
        }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_ctrl_d)?.setOnClickListener {
            onSendControlChar('d')
        }

        // Row 3: Navigation (requires accessibility service)
        hotkeyBar.findViewById<Button>(R.id.btn_hk_home)?.setOnClickListener {
            onSendSystemKey(android.view.KeyEvent.KEYCODE_HOME)
        }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_back)?.setOnClickListener {
            onSendSystemKey(android.view.KeyEvent.KEYCODE_BACK)
        }
        hotkeyBar.findViewById<Button>(R.id.btn_hk_recent)?.setOnClickListener {
            onSendSystemKey(android.view.KeyEvent.KEYCODE_APP_SWITCH)
        }
    }

    fun setHotkeyBarVisibility(visible: Boolean) {
        hotkeyBar?.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
