/*
 * Activity for configuring controller button bindings.
 * Shows an expandable list of buttons with their current actions.
 */

package com.example.whispertoinput.controller

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ExpandableListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.whispertoinput.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ButtonBindingsActivity : AppCompatActivity() {

    private lateinit var bindingsManager: ButtonBindingsManager
    private lateinit var expandableListView: ExpandableListView
    private lateinit var adapter: ButtonBindingsAdapter
    private lateinit var tmuxPrefixText: TextView

    // Group structure: "Normal" buttons and "With R1" buttons
    private val groups = listOf("Normal", "With R1 Modifier")
    private lateinit var normalButtons: List<ButtonKey>
    private lateinit var r1Buttons: List<ButtonKey>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_button_bindings)

        bindingsManager = ButtonBindingsManager(this)
        expandableListView = findViewById(R.id.expandable_list_bindings)
        tmuxPrefixText = findViewById(R.id.tmux_prefix_value)

        normalButtons = ButtonKey.allButtons()
        r1Buttons = ButtonKey.allButtonsWithR1()

        adapter = ButtonBindingsAdapter()
        expandableListView.setAdapter(adapter)

        // Expand both groups by default
        expandableListView.expandGroup(0)
        expandableListView.expandGroup(1)

        // Handle item clicks
        expandableListView.setOnChildClickListener { _, _, groupPosition, childPosition, _ ->
            val buttonKey = if (groupPosition == 0) {
                normalButtons[childPosition]
            } else {
                r1Buttons[childPosition]
            }
            showActionPicker(buttonKey)
            true
        }

        // Tmux prefix configuration
        findViewById<View>(R.id.tmux_prefix_row).setOnClickListener {
            showTmuxPrefixPicker()
        }

        // Reset button
        findViewById<Button>(R.id.btn_reset_bindings).setOnClickListener {
            showResetConfirmation()
        }

        loadBindings()
    }

    private fun loadBindings() {
        CoroutineScope(Dispatchers.Main).launch {
            adapter.bindings = withContext(Dispatchers.IO) {
                bindingsManager.getBindings()
            }
            val prefix = withContext(Dispatchers.IO) {
                bindingsManager.getTmuxPrefix()
            }
            tmuxPrefixText.text = "Ctrl+${prefix.uppercaseChar()}"
            adapter.notifyDataSetChanged()
        }
    }

    private fun showActionPicker(buttonKey: ButtonKey) {
        val categories = ActionType.byCategory()
        val categoryNames = categories.keys.toList()

        // Build flat list with headers
        val items = mutableListOf<Pair<String, ActionType?>>()
        for (category in categoryNames) {
            items.add(Pair("— $category —", null))
            for (action in categories[category]!!) {
                items.add(Pair(action.displayName, action))
            }
        }

        val itemStrings = items.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Action for ${buttonKey.displayName()}")
            .setItems(itemStrings) { _, which ->
                val selected = items[which]
                if (selected.second != null) {
                    setBinding(buttonKey, selected.second!!)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setBinding(buttonKey: ButtonKey, action: ActionType) {
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                bindingsManager.setAction(buttonKey, action)
            }
            loadBindings()
        }
    }

    private fun showTmuxPrefixPicker() {
        val letters = ('a'..'z').map { "Ctrl+${it.uppercaseChar()}" }.toTypedArray()

        CoroutineScope(Dispatchers.Main).launch {
            val currentPrefix = withContext(Dispatchers.IO) {
                bindingsManager.getTmuxPrefix()
            }
            val currentIndex = currentPrefix - 'a'

            AlertDialog.Builder(this@ButtonBindingsActivity)
                .setTitle("Tmux Prefix Key")
                .setSingleChoiceItems(letters, currentIndex) { dialog, which ->
                    val newPrefix = 'a' + which
                    setTmuxPrefix(newPrefix)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setTmuxPrefix(prefix: Char) {
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                bindingsManager.setTmuxPrefix(prefix)
            }
            tmuxPrefixText.text = "Ctrl+${prefix.uppercaseChar()}"
        }
    }

    private fun showResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Reset Bindings")
            .setMessage("Reset all button bindings to defaults?")
            .setPositiveButton("Reset") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    withContext(Dispatchers.IO) {
                        bindingsManager.resetToDefaults()
                    }
                    loadBindings()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class ButtonBindingsAdapter : BaseExpandableListAdapter() {

        var bindings: Map<String, ActionType> = emptyMap()

        override fun getGroupCount(): Int = groups.size

        override fun getChildrenCount(groupPosition: Int): Int {
            return if (groupPosition == 0) normalButtons.size else r1Buttons.size
        }

        override fun getGroup(groupPosition: Int): String = groups[groupPosition]

        override fun getChild(groupPosition: Int, childPosition: Int): ButtonKey {
            return if (groupPosition == 0) {
                normalButtons[childPosition]
            } else {
                r1Buttons[childPosition]
            }
        }

        override fun getGroupId(groupPosition: Int): Long = groupPosition.toLong()

        override fun getChildId(groupPosition: Int, childPosition: Int): Long {
            return (groupPosition * 100 + childPosition).toLong()
        }

        override fun hasStableIds(): Boolean = true

        override fun getGroupView(
            groupPosition: Int,
            isExpanded: Boolean,
            convertView: View?,
            parent: ViewGroup?
        ): View {
            val view = convertView ?: LayoutInflater.from(this@ButtonBindingsActivity)
                .inflate(android.R.layout.simple_expandable_list_item_1, parent, false)

            val textView = view.findViewById<TextView>(android.R.id.text1)
            textView.text = groups[groupPosition]
            textView.setPadding(100, 20, 20, 20)
            textView.textSize = 18f

            return view
        }

        override fun getChildView(
            groupPosition: Int,
            childPosition: Int,
            isLastChild: Boolean,
            convertView: View?,
            parent: ViewGroup?
        ): View {
            val view = convertView ?: LayoutInflater.from(this@ButtonBindingsActivity)
                .inflate(R.layout.item_button_binding, parent, false)

            val buttonKey = getChild(groupPosition, childPosition)
            val action = bindings[buttonKey.toStorageKey()] ?: ActionType.NONE

            view.findViewById<TextView>(R.id.button_name).text = buttonKey.displayName()
            view.findViewById<TextView>(R.id.action_name).text = action.displayName

            return view
        }

        override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean = true
    }
}
