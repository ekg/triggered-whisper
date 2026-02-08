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

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.whispertoinput.controller.ButtonBindingsActivity
import android.net.Uri
import android.provider.*
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

// 200 and 201 are an arbitrary values, as long as they do not conflict with each other
private const val MICROPHONE_PERMISSION_REQUEST_CODE = 200
private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 201
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val SPEECH_TO_TEXT_BACKEND = stringPreferencesKey("speech-to-text-backend")
val ENDPOINT = stringPreferencesKey("endpoint")
val LANGUAGE_CODE = stringPreferencesKey("language-code")
val API_KEY = stringPreferencesKey("api-key")
val MODEL = stringPreferencesKey("model")
val POSTPROCESSING = stringPreferencesKey("postprocessing")
val ADD_TRAILING_SPACE = booleanPreferencesKey("add-trailing-space")
val AUTO_SWITCH_BACK = booleanPreferencesKey("auto-switch-back")
val FLOATING_KEYBOARD_LANDSCAPE = booleanPreferencesKey("floating-keyboard-landscape")
val ENABLE_NAVIGATION_SERVICE = booleanPreferencesKey("enable-navigation-service")
val SHOW_HOTKEY_BAR = booleanPreferencesKey("show-hotkey-bar")

class MainActivity : AppCompatActivity() {
    private var setupSettingItemsDone: Boolean = false
    private val joystickKeyHistory = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupSettingItems()
        checkPermissions()

        // Button bindings configuration
        findViewById<Button>(R.id.btn_configure_bindings).setOnClickListener {
            startActivity(Intent(this, ButtonBindingsActivity::class.java))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val keyName = KeyEvent.keyCodeToString(keyCode)
        val shortName = keyName.removePrefix("KEYCODE_")

        // Add to history (keep last 5 entries)
        joystickKeyHistory.add(0, "$keyCode: $shortName")
        if (joystickKeyHistory.size > 5) {
            joystickKeyHistory.removeAt(joystickKeyHistory.size - 1)
        }

        // Update display
        val displayText = joystickKeyHistory.joinToString("\n")
        findViewById<TextView>(R.id.joystick_test_display)?.text = displayText.ifEmpty { "Press any button..." }

        return super.onKeyDown(keyCode, event)
    }

    // The onClick event of the grant permission button.
    // Opens up the app settings panel to manually configure permissions.
    fun onRequestMicrophonePermission(view: View) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        with(intent) {
            data = Uri.fromParts("package", packageName, null)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        startActivity(intent)
    }

    // Checks whether permissions are granted. If not, automatically make a request.
    private fun checkPermissions() {
        val permission_and_code = arrayOf(
            Pair(Manifest.permission.RECORD_AUDIO, MICROPHONE_PERMISSION_REQUEST_CODE),
            Pair(Manifest.permission.POST_NOTIFICATIONS, NOTIFICATION_PERMISSION_REQUEST_CODE),
        )
        for ((permission, code) in permission_and_code) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) == PackageManager.PERMISSION_DENIED
            ) {
                // Shows a popup for permission request.
                // If the permission has been previously (hard-)denied, the popup will not show.
                // onRequestPermissionsResult will be called in either case.
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(permission),
                    code
                )
            }
        }
    }

    // Handles the results of permission requests.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        var msg: String

        // Only handles requests marked with the unique code.
        if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
            msg = getString(R.string.mic_permission_required)
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            msg = getString(R.string.notification_permission_required)
        } else {
            return
        }

        // All permissions should be granted.
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return
            }
        }
    }

    // Check if accessibility service is enabled
    private fun isAccessibilityServiceEnabled(): Boolean {
        // Use the manifest namespace, not applicationId
        val expectedComponentName = "com.example.whispertoinput/${NavigationAccessibilityService::class.java.name}"
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = enabledServicesSetting.split(":")
        return colonSplitter.any { componentString ->
            componentString.equals(expectedComponentName, ignoreCase = true)
        }
    }

    // Below are settings related functions
    abstract inner class SettingItem() {
        protected var isDirty: Boolean = false
        abstract fun setup() : Job
        abstract suspend fun apply()
        protected suspend fun <T> readSetting(key: Preferences.Key<T>): T? {
            return dataStore.data.map { preferences ->
                preferences[key]
            }.first()
        }
        protected suspend fun <T> writeSetting(key: Preferences.Key<T>, newValue: T) {
            dataStore.edit { settings ->
                settings[key] = newValue
            }
        }
    }

    inner class SettingDropdown(
        private val viewId: Int,
        private val preferenceKey: Preferences.Key<Boolean>,
        private val stringToValue: HashMap<String, Boolean>,
        private val defaultValue: Boolean = true
    ): SettingItem() {
        override fun setup(): Job {
            return CoroutineScope(Dispatchers.Main).launch {
                val btnApply: Button = findViewById(R.id.btn_settings_apply)
                val spinner = findViewById<Spinner>(viewId)
                spinner.isEnabled = false
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                        if (!setupSettingItemsDone) return
                        isDirty = true
                        btnApply.isEnabled = true
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) { }
                }

                val valueToString = stringToValue.map { (k, v) -> v to k }.toMap()
                // Read data. If none, apply default value.
                val settingValue: Boolean? = readSetting(preferenceKey)
                val value: Boolean = settingValue ?: defaultValue
                val string: String = valueToString[value]!!
                if (settingValue == null) {
                    writeSetting(preferenceKey, defaultValue)
                }
                val index: Int? = (0 until spinner.adapter.count).firstOrNull {
                    spinner.adapter.getItem(it) == string
                }
                spinner.setSelection(index!!, false)
                spinner.isEnabled = true
            }
        }
        override suspend fun apply() {
            if (!isDirty) return
            val selectedItem = findViewById<Spinner>(viewId).selectedItem
            val newValue: Boolean = stringToValue[selectedItem]!!
            writeSetting(preferenceKey, newValue)
            isDirty = false
        }
    }

    inner class SettingStringDropdown(
        private val viewId: Int,
        private val preferenceKey: Preferences.Key<String>,
        private val defaultValue: String
    ): SettingItem() {
        override fun setup(): Job {
            return CoroutineScope(Dispatchers.Main).launch {
                val btnApply: Button = findViewById(R.id.btn_settings_apply)
                val spinner = findViewById<Spinner>(viewId)
                spinner.isEnabled = false
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                        if (!setupSettingItemsDone) return
                        isDirty = true
                        btnApply.isEnabled = true
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) { }
                }

                // Read data. If none, apply default value.
                val settingValue: String? = readSetting(preferenceKey)
                val value: String = settingValue ?: defaultValue
                if (settingValue == null) {
                    writeSetting(preferenceKey, defaultValue)
                }
                val index: Int? = (0 until spinner.adapter.count).firstOrNull {
                    spinner.adapter.getItem(it) == value
                }
                if (index != null) {
                    spinner.setSelection(index, false)
                }
                spinner.isEnabled = true
            }
        }
        override suspend fun apply() {
            if (!isDirty) return
            val selectedItem = findViewById<Spinner>(viewId).selectedItem as String
            writeSetting(preferenceKey, selectedItem)
            isDirty = false
        }
    }

    inner class SettingTextInput(
        private val viewId: Int,
        private val preferenceKey: Preferences.Key<String>,
        private val defaultValue: String
    ): SettingItem() {
        override fun setup(): Job {
            return CoroutineScope(Dispatchers.Main).launch {
                val btnApply: Button = findViewById(R.id.btn_settings_apply)
                val editText = findViewById<EditText>(viewId)

                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && setupSettingItemsDone) {
                        isDirty = true
                        btnApply.isEnabled = true
                    }
                }

                // Read data. If none or empty, apply default value.
                val settingValue: String? = readSetting(preferenceKey)
                val value: String = if (settingValue.isNullOrEmpty()) defaultValue else settingValue
                if (settingValue.isNullOrEmpty()) {
                    writeSetting(preferenceKey, defaultValue)
                }
                editText.setText(value)
            }
        }
        override suspend fun apply() {
            val editText = findViewById<EditText>(viewId)
            val newValue = editText.text.toString()
            writeSetting(preferenceKey, newValue)
            isDirty = false
        }
    }

    // Hidden setting - just sets default value, no UI
    inner class SettingHidden(
        private val preferenceKey: Preferences.Key<String>,
        private val defaultValue: String
    ): SettingItem() {
        override fun setup(): Job {
            return CoroutineScope(Dispatchers.Main).launch {
                val settingValue: String? = readSetting(preferenceKey)
                if (settingValue.isNullOrEmpty()) {
                    writeSetting(preferenceKey, defaultValue)
                }
            }
        }
        override suspend fun apply() {
            // No-op for hidden settings
        }
    }

    private fun setupSettingItems() {
        setupSettingItemsDone = false
        // Add setting items here to apply functions to them
        CoroutineScope(Dispatchers.Main).launch {
            val settingItems = arrayOf(
                SettingStringDropdown(R.id.spinner_speech_to_text_backend, SPEECH_TO_TEXT_BACKEND,
                    getString(R.string.settings_option_openai_api)),
                SettingTextInput(R.id.edittext_endpoint, ENDPOINT,
                    getString(R.string.settings_option_openai_api_default_endpoint)),
                SettingTextInput(R.id.edittext_api_key, API_KEY, ""),
                // Set default model for OpenAI (gpt-4o-transcribe)
                SettingHidden(MODEL, "gpt-4o-transcribe"),
                SettingDropdown(R.id.spinner_auto_switch_back, AUTO_SWITCH_BACK, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                ), false),
                SettingDropdown(R.id.spinner_floating_keyboard_landscape, FLOATING_KEYBOARD_LANDSCAPE, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                ), false),
                SettingDropdown(R.id.spinner_enable_navigation_service, ENABLE_NAVIGATION_SERVICE, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                ), false),
                SettingDropdown(R.id.spinner_show_hotkey_bar, SHOW_HOTKEY_BAR, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                ), false),
            )
            val btnApply: Button = findViewById(R.id.btn_settings_apply)
            btnApply.isEnabled = false
            btnApply.setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    btnApply.isEnabled = false
                    for (settingItem in settingItems) {
                        settingItem.apply()
                    }
                    btnApply.isEnabled = false

                    // Check if floating keyboard was enabled and request permission if needed
                    val floatingEnabled = dataStore.data.map { preferences ->
                        preferences[FLOATING_KEYBOARD_LANDSCAPE] ?: false
                    }.first()

                    var needsPermission = false
                    if (floatingEnabled) {
                        // Check if we have overlay permission
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            if (!Settings.canDrawOverlays(this@MainActivity)) {
                                // Need to request overlay permission - go to app permissions page
                                needsPermission = true
                                Toast.makeText(this@MainActivity, "Opening Triggered Whisper permissions. Enable 'Display over other apps'", Toast.LENGTH_LONG).show()
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    intent.data = Uri.parse("package:$packageName")
                                    intent.addCategory(Intent.CATEGORY_DEFAULT)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(this@MainActivity, "Please enable 'Display over other apps' in Android Settings > Apps > Triggered Whisper", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }

                    // Check if navigation service was enabled and request accessibility permission if needed
                    val navigationEnabled = dataStore.data.map { preferences ->
                        preferences[ENABLE_NAVIGATION_SERVICE] ?: false
                    }.first()

                    if (navigationEnabled && !needsPermission) {
                        // Check if accessibility service is enabled
                        if (!isAccessibilityServiceEnabled()) {
                            needsPermission = true
                            Toast.makeText(this@MainActivity, "Opening Accessibility settings. Enable 'Triggered Whisper Navigation'", Toast.LENGTH_LONG).show()
                            try {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Please enable 'Triggered Whisper Navigation' in Android Settings > Accessibility", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    if (!needsPermission) {
                        Toast.makeText(this@MainActivity, R.string.successfully_set, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            settingItems.map { settingItem -> settingItem.setup() }.joinAll()
            setupSettingItemsDone = true
        }
    }
}
