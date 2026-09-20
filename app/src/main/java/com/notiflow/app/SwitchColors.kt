package com.lexlebeau.notiflow

import android.content.Context
import android.content.res.ColorStateList
import android.widget.Switch
import androidx.core.content.ContextCompat

/** Красит Switch в акцент "успех" (вкл) / приглушённый цвет (выкл) — с учётом текущей темы. */
fun styleSwitch(context: Context, switch: Switch) {
    val thumbOn = ContextCompat.getColor(context, R.color.color_accent_success)
    val thumbOff = ContextCompat.getColor(context, R.color.color_text_tertiary)
    val trackOn = ContextCompat.getColor(context, R.color.color_accent_success_translucent)
    val trackOff = ContextCompat.getColor(context, R.color.color_switch_track_off)

    switch.thumbTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(thumbOn, thumbOff)
    )
    switch.trackTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(trackOn, trackOff)
    )
}
