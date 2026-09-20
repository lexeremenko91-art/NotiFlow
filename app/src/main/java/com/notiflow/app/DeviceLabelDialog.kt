package com.lexlebeau.notiflow

import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

/** Показывает диалог ввода/переименования человекочитаемого имени спаренного устройства. */
fun showDeviceLabelDialog(
    context: Context,
    currentLabel: String,
    onSave: (String) -> Unit
) {
    val input = EditText(context)
    input.setText(currentLabel)
    input.hint = context.getString(R.string.device_name_hint)
    val padding = (20 * context.resources.displayMetrics.density).toInt()
    input.setPadding(padding, padding, padding, padding)

    AlertDialog.Builder(context)
        .setTitle(context.getString(R.string.device_name_dialog_title))
        .setView(input)
        .setPositiveButton(context.getString(R.string.btn_save)) { _, _ ->
            val newLabel = input.text.toString().trim()
            onSave(if (newLabel.isEmpty()) currentLabel else newLabel)
        }
        .setNegativeButton(context.getString(R.string.btn_cancel), null)
        .show()
}
