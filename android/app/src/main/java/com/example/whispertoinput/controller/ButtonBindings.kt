/*
 * Controller button binding configuration.
 * Allows users to customize what each controller button does.
 */

package com.example.whispertoinput.controller

import android.content.Context
import android.view.KeyEvent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.whispertoinput.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

// DataStore key for bindings
val BUTTON_BINDINGS = stringPreferencesKey("button-bindings")
val TMUX_PREFIX = stringPreferencesKey("tmux-prefix")

/**
 * Types of actions that can be bound to controller buttons.
 */
enum class ActionType(val displayName: String, val category: String) {
    // Voice
    VOICE_INPUT("Voice Input", "Voice"),

    // Basic Keys
    KEY_ENTER("Enter", "Keys"),
    KEY_SPACE("Space", "Keys"),
    KEY_DELETE("Delete/Backspace", "Keys"),
    KEY_TAB("Tab", "Keys"),
    KEY_ESCAPE("Escape", "Keys"),

    // Arrow Keys
    KEY_UP("Up Arrow", "Arrow Keys"),
    KEY_DOWN("Down Arrow", "Arrow Keys"),
    KEY_LEFT("Left Arrow", "Arrow Keys"),
    KEY_RIGHT("Right Arrow", "Arrow Keys"),

    // Control Characters
    CTRL_A("Ctrl+A", "Control"),
    CTRL_B("Ctrl+B", "Control"),
    CTRL_C("Ctrl+C (Cancel)", "Control"),
    CTRL_D("Ctrl+D (Exit/EOF)", "Control"),
    CTRL_E("Ctrl+E", "Control"),
    CTRL_F("Ctrl+F", "Control"),
    CTRL_G("Ctrl+G", "Control"),
    CTRL_H("Ctrl+H", "Control"),
    CTRL_I("Ctrl+I", "Control"),
    CTRL_J("Ctrl+J", "Control"),
    CTRL_K("Ctrl+K", "Control"),
    CTRL_L("Ctrl+L (Clear)", "Control"),
    CTRL_M("Ctrl+M", "Control"),
    CTRL_N("Ctrl+N", "Control"),
    CTRL_O("Ctrl+O", "Control"),
    CTRL_P("Ctrl+P", "Control"),
    CTRL_Q("Ctrl+Q", "Control"),
    CTRL_R("Ctrl+R (Reverse Search)", "Control"),
    CTRL_S("Ctrl+S", "Control"),
    CTRL_T("Ctrl+T", "Control"),
    CTRL_U("Ctrl+U (Clear Line)", "Control"),
    CTRL_V("Ctrl+V", "Control"),
    CTRL_W("Ctrl+W (Delete Word)", "Control"),
    CTRL_X("Ctrl+X", "Control"),
    CTRL_Y("Ctrl+Y", "Control"),
    CTRL_Z("Ctrl+Z (Suspend)", "Control"),

    // Tmux (uses configurable prefix)
    TMUX_NEW_PANE_H("Tmux: New Pane (horizontal)", "Tmux"),
    TMUX_NEW_PANE_V("Tmux: New Pane (vertical)", "Tmux"),
    TMUX_NEW_WINDOW("Tmux: New Window", "Tmux"),
    TMUX_NEXT_WINDOW("Tmux: Next Window", "Tmux"),
    TMUX_PREV_WINDOW("Tmux: Previous Window", "Tmux"),
    TMUX_NEXT_PANE("Tmux: Next Pane", "Tmux"),
    TMUX_PREV_PANE("Tmux: Previous Pane", "Tmux"),
    TMUX_COMMAND("Tmux: Command Mode", "Tmux"),
    TMUX_DETACH("Tmux: Detach", "Tmux"),
    TMUX_COPY_MODE("Tmux: Copy Mode", "Tmux"),

    // System
    SYSTEM_HOME("Home", "System"),
    SYSTEM_BACK("Back", "System"),
    SYSTEM_RECENTS("Recent Apps", "System"),

    // None
    NONE("(No action)", "None");

    companion object {
        fun fromString(s: String): ActionType {
            return try {
                valueOf(s)
            } catch (e: Exception) {
                NONE
            }
        }

        fun byCategory(): Map<String, List<ActionType>> {
            return values().groupBy { it.category }
        }
    }
}

/**
 * Represents a button on the controller, optionally with R1 modifier.
 */
data class ButtonKey(
    val keyCode: Int,
    val withR1Modifier: Boolean = false
) {
    fun toStorageKey(): String {
        val base = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
            KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
            KeyEvent.KEYCODE_BUTTON_A -> "A"
            KeyEvent.KEYCODE_BUTTON_B -> "B"
            KeyEvent.KEYCODE_BUTTON_X -> "X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Y"
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            else -> "KEY_$keyCode"
        }
        return if (withR1Modifier) "${base}+R1" else base
    }

    fun displayName(): String {
        val base = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
            KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
            KeyEvent.KEYCODE_BUTTON_A -> "A"
            KeyEvent.KEYCODE_BUTTON_B -> "B"
            KeyEvent.KEYCODE_BUTTON_X -> "X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Y"
            KeyEvent.KEYCODE_DPAD_UP -> "D-pad Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "D-pad Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "D-pad Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "D-pad Right"
            else -> "Button $keyCode"
        }
        return if (withR1Modifier) "$base (+ R1)" else base
    }

    companion object {
        fun fromStorageKey(key: String): ButtonKey? {
            val withR1 = key.endsWith("+R1")
            val base = if (withR1) key.removeSuffix("+R1") else key

            val keyCode = when (base) {
                "L1" -> KeyEvent.KEYCODE_BUTTON_L1
                "R1" -> KeyEvent.KEYCODE_BUTTON_R1
                "L2" -> KeyEvent.KEYCODE_BUTTON_L2
                "R2" -> KeyEvent.KEYCODE_BUTTON_R2
                "A" -> KeyEvent.KEYCODE_BUTTON_A
                "B" -> KeyEvent.KEYCODE_BUTTON_B
                "X" -> KeyEvent.KEYCODE_BUTTON_X
                "Y" -> KeyEvent.KEYCODE_BUTTON_Y
                "UP" -> KeyEvent.KEYCODE_DPAD_UP
                "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
                "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
                "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
                else -> return null
            }

            return ButtonKey(keyCode, withR1)
        }

        /**
         * All configurable buttons (without R1 modifier).
         * R1 itself is always the modifier key.
         */
        fun allButtons(): List<ButtonKey> {
            return listOf(
                ButtonKey(KeyEvent.KEYCODE_BUTTON_L1),
                ButtonKey(KeyEvent.KEYCODE_BUTTON_L2),
                ButtonKey(KeyEvent.KEYCODE_BUTTON_R2),
                ButtonKey(KeyEvent.KEYCODE_BUTTON_A),
                ButtonKey(KeyEvent.KEYCODE_BUTTON_B),
                ButtonKey(KeyEvent.KEYCODE_BUTTON_X),
                ButtonKey(KeyEvent.KEYCODE_BUTTON_Y),
                ButtonKey(KeyEvent.KEYCODE_DPAD_UP),
                ButtonKey(KeyEvent.KEYCODE_DPAD_DOWN),
                ButtonKey(KeyEvent.KEYCODE_DPAD_LEFT),
                ButtonKey(KeyEvent.KEYCODE_DPAD_RIGHT),
            )
        }

        /**
         * All configurable buttons with R1 modifier.
         */
        fun allButtonsWithR1(): List<ButtonKey> {
            return allButtons().map { it.copy(withR1Modifier = true) }
        }
    }
}

/**
 * Manages button bindings storage and lookup.
 */
class ButtonBindingsManager(private val context: Context) {

    private var cachedBindings: MutableMap<String, ActionType>? = null
    private var cachedTmuxPrefix: Char = 'a'  // Default Ctrl+A
    private var isCacheLoaded: Boolean = false

    /**
     * Get the action for a button press synchronously (uses cache).
     * Returns NONE if cache not loaded yet.
     */
    fun getActionSync(buttonKey: ButtonKey): ActionType {
        val bindings = cachedBindings ?: return ActionType.NONE
        return bindings[buttonKey.toStorageKey()] ?: ActionType.NONE
    }

    /**
     * Get cached tmux prefix synchronously.
     */
    fun getTmuxPrefixSync(): Char {
        return cachedTmuxPrefix
    }

    /**
     * Check if bindings cache is loaded.
     */
    fun isCacheReady(): Boolean = isCacheLoaded

    /**
     * Load bindings into cache (call from coroutine on service start).
     */
    suspend fun loadCache() {
        getBindings()  // This populates cachedBindings
        getTmuxPrefix()  // This populates cachedTmuxPrefix
        isCacheLoaded = true
    }

    /**
     * Get the action for a button press.
     */
    suspend fun getAction(buttonKey: ButtonKey): ActionType {
        val bindings = getBindings()
        return bindings[buttonKey.toStorageKey()] ?: ActionType.NONE
    }

    /**
     * Set the action for a button.
     */
    suspend fun setAction(buttonKey: ButtonKey, action: ActionType) {
        val bindings = getBindings().toMutableMap()
        bindings[buttonKey.toStorageKey()] = action
        saveBindings(bindings)
    }

    /**
     * Get all current bindings.
     */
    suspend fun getBindings(): Map<String, ActionType> {
        if (cachedBindings != null) {
            return cachedBindings!!
        }

        val json = context.dataStore.data.map { prefs ->
            prefs[BUTTON_BINDINGS]
        }.first()

        val bindings = if (json.isNullOrEmpty()) {
            getDefaultBindings().toMutableMap()
        } else {
            parseBindings(json).toMutableMap()
        }

        cachedBindings = bindings
        return bindings
    }

    /**
     * Save bindings to storage.
     */
    private suspend fun saveBindings(bindings: Map<String, ActionType>) {
        cachedBindings = bindings.toMutableMap()
        val json = serializeBindings(bindings)
        context.dataStore.edit { prefs ->
            prefs[BUTTON_BINDINGS] = json
        }
    }

    /**
     * Get the tmux prefix character (for Ctrl+prefix).
     */
    suspend fun getTmuxPrefix(): Char {
        val stored = context.dataStore.data.map { prefs ->
            prefs[TMUX_PREFIX]
        }.first()

        cachedTmuxPrefix = stored?.firstOrNull() ?: 'a'
        return cachedTmuxPrefix
    }

    /**
     * Set the tmux prefix character.
     */
    suspend fun setTmuxPrefix(prefix: Char) {
        cachedTmuxPrefix = prefix
        context.dataStore.edit { prefs ->
            prefs[TMUX_PREFIX] = prefix.toString()
        }
    }

    /**
     * Reset to default bindings.
     */
    suspend fun resetToDefaults() {
        saveBindings(getDefaultBindings())
        setTmuxPrefix('a')
    }

    /**
     * Get the control character for a Ctrl+X action.
     */
    fun getControlChar(action: ActionType): Char? {
        return when (action) {
            ActionType.CTRL_A -> 'a'
            ActionType.CTRL_B -> 'b'
            ActionType.CTRL_C -> 'c'
            ActionType.CTRL_D -> 'd'
            ActionType.CTRL_E -> 'e'
            ActionType.CTRL_F -> 'f'
            ActionType.CTRL_G -> 'g'
            ActionType.CTRL_H -> 'h'
            ActionType.CTRL_I -> 'i'
            ActionType.CTRL_J -> 'j'
            ActionType.CTRL_K -> 'k'
            ActionType.CTRL_L -> 'l'
            ActionType.CTRL_M -> 'm'
            ActionType.CTRL_N -> 'n'
            ActionType.CTRL_O -> 'o'
            ActionType.CTRL_P -> 'p'
            ActionType.CTRL_Q -> 'q'
            ActionType.CTRL_R -> 'r'
            ActionType.CTRL_S -> 's'
            ActionType.CTRL_T -> 't'
            ActionType.CTRL_U -> 'u'
            ActionType.CTRL_V -> 'v'
            ActionType.CTRL_W -> 'w'
            ActionType.CTRL_X -> 'x'
            ActionType.CTRL_Y -> 'y'
            ActionType.CTRL_Z -> 'z'
            else -> null
        }
    }

    /**
     * Get the tmux command character for a tmux action.
     */
    fun getTmuxCommand(action: ActionType): Char? {
        return when (action) {
            ActionType.TMUX_NEW_PANE_H -> '"'
            ActionType.TMUX_NEW_PANE_V -> '%'
            ActionType.TMUX_NEW_WINDOW -> 'c'
            ActionType.TMUX_NEXT_WINDOW -> 'n'
            ActionType.TMUX_PREV_WINDOW -> 'p'
            ActionType.TMUX_NEXT_PANE -> 'o'
            ActionType.TMUX_PREV_PANE -> ';'
            ActionType.TMUX_COMMAND -> ':'
            ActionType.TMUX_DETACH -> 'd'
            ActionType.TMUX_COPY_MODE -> '['
            else -> null
        }
    }

    companion object {
        /**
         * Default button bindings matching the original hardcoded behavior.
         */
        fun getDefaultBindings(): Map<String, ActionType> {
            return mapOf(
                // Basic buttons
                "L1" to ActionType.VOICE_INPUT,
                "A" to ActionType.CTRL_R,
                "B" to ActionType.KEY_ENTER,
                "X" to ActionType.KEY_DELETE,
                "Y" to ActionType.KEY_SPACE,
                "L2" to ActionType.TMUX_PREV_WINDOW,
                "R2" to ActionType.TMUX_NEXT_WINDOW,

                // With R1 modifier
                "L1+R1" to ActionType.TMUX_NEW_PANE_H,
                "L2+R1" to ActionType.TMUX_NEW_WINDOW,
                "A+R1" to ActionType.CTRL_A,  // Tmux prefix (for command mode)
                "X+R1" to ActionType.CTRL_C,
                "Y+R1" to ActionType.CTRL_D,
                "UP+R1" to ActionType.SYSTEM_HOME,
                "DOWN+R1" to ActionType.SYSTEM_RECENTS,
                "LEFT+R1" to ActionType.SYSTEM_BACK,
            )
        }

        private fun parseBindings(json: String): Map<String, ActionType> {
            val result = mutableMapOf<String, ActionType>()
            try {
                val obj = JSONObject(json)
                for (key in obj.keys()) {
                    val actionStr = obj.getString(key)
                    result[key] = ActionType.fromString(actionStr)
                }
            } catch (e: Exception) {
                return getDefaultBindings()
            }
            return result
        }

        private fun serializeBindings(bindings: Map<String, ActionType>): String {
            val obj = JSONObject()
            for ((key, action) in bindings) {
                obj.put(key, action.name)
            }
            return obj.toString()
        }
    }
}
