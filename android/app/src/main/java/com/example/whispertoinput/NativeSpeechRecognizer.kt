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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Wrapper for Android's native SpeechRecognizer API.
 * Provides streaming speech-to-text with real-time partial results.
 */
class NativeSpeechRecognizer(
    private val context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onRmsChanged: (Float) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening: Boolean = false
    private var lastPartialResult: String = ""

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d("NativeSpeechRecognizer", "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d("NativeSpeechRecognizer", "Beginning of speech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Convert RMS dB to amplitude-like value (0-32767 range like RecorderManager)
            // RMS dB typically ranges from -2 to 10
            val normalizedAmplitude = ((rmsdB + 2) / 12 * 32767).coerceIn(0f, 32767f)
            onRmsChanged(normalizedAmplitude)
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Not used for our purposes
        }

        override fun onEndOfSpeech() {
            Log.d("NativeSpeechRecognizer", "End of speech")
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown error: $error"
            }
            Log.e("NativeSpeechRecognizer", "Error: $errorMessage")
            isListening = false

            // Don't report "No speech recognized" or "No speech input" as errors
            // - these are normal when user stops without speaking
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                onError(errorMessage)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val finalText = matches[0]
                Log.d("NativeSpeechRecognizer", "Final result: $finalText")
                onFinalResult(finalText)
            }
            isListening = false
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val partialText = matches[0]
                if (partialText != lastPartialResult) {
                    Log.d("NativeSpeechRecognizer", "Partial result: $partialText")
                    lastPartialResult = partialText
                    onPartialResult(partialText)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d("NativeSpeechRecognizer", "Event: $eventType")
        }
    }

    fun start() {
        if (isListening) {
            Log.w("NativeSpeechRecognizer", "Already listening")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available on this device")
            return
        }

        lastPartialResult = ""

        CoroutineScope(Dispatchers.Main).launch {
            // Get language setting
            val languageCode = context.dataStore.data.map { preferences ->
                preferences[LANGUAGE_CODE] ?: ""
            }.first()

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

                // Use language setting if set, otherwise use device default
                if (languageCode.isNotEmpty() && languageCode != "auto") {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
                } else {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                }
            }

            isListening = true
            speechRecognizer?.startListening(intent)
            Log.d("NativeSpeechRecognizer", "Started listening")
        }
    }

    fun stop() {
        if (!isListening) {
            return
        }

        Log.d("NativeSpeechRecognizer", "Stopping...")
        isListening = false
        speechRecognizer?.stopListening()
    }

    fun cancel() {
        Log.d("NativeSpeechRecognizer", "Cancelling...")
        isListening = false
        speechRecognizer?.cancel()
    }

    fun destroy() {
        Log.d("NativeSpeechRecognizer", "Destroying...")
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun isListening(): Boolean = isListening
}
