/*
 * Copyright (C) 2022 FlamingoOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.alertslider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Handler
import android.util.Log

import com.android.internal.os.AlertSlider.Mode
import com.android.internal.os.AlertSlider.Position
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.ScreenLifecycle

import javax.inject.Inject

private val TAG = AlertSliderController::class.simpleName!!
private const val TIMEOUT = 1000L

@SysUISingleton
class AlertSliderController @Inject constructor(
    private val context: Context,
    @Main private val handler: Handler,
    private val dialog: AlertSliderDialog,
    private val screenLifecycle: ScreenLifecycle
) {

    private val sliderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SLIDER_POSITION_CHANGED) return
            val modeString = intent.getStringExtra(Intent.EXTRA_SLIDER_MODE)
            val mode = try {
                Mode.valueOf(modeString)
            } catch(_: IllegalArgumentException) {
                Log.e(TAG, "Unknown mode $modeString")
                return
            }
            val positionString = intent.getStringExtra(Intent.EXTRA_SLIDER_POSITION)
            val position = try {
                Position.valueOf(positionString)
            } catch(_: IllegalArgumentException) {
                Log.e(TAG, "Unknown position $positionString")
                return
            }
            updateDialog(mode)
            showDialog(position)
        }
    }

    private val dismissDialogRunnable = Runnable { dialog.dismiss() }

    fun start() {
        context.registerReceiver(
            sliderReceiver,
            IntentFilter(Intent.ACTION_SLIDER_POSITION_CHANGED)
        )
    }

    fun updateConfiguration(newConfig: Configuration) {
        removeHandlerCalbacks()
        dialog.updateConfiguration(newConfig)
    }

    private fun updateDialog(mode: Mode) {
        when (mode) {
            Mode.NORMAL -> dialog.setIconAndLabel(
                R.drawable.ic_volume_ringer,
                R.string.volume_ringer_status_normal
            )
            Mode.PRIORITY -> dialog.setIconAndLabel(
                com.android.internal.R.drawable.ic_qs_dnd,
                R.string.alert_slider_mode_priority_text
            )
            Mode.VIBRATE -> dialog.setIconAndLabel(
                R.drawable.ic_volume_ringer_vibrate,
                R.string.volume_ringer_status_vibrate
            )
            Mode.SILENT -> dialog.setIconAndLabel(
                R.drawable.ic_volume_ringer_mute,
                R.string.volume_ringer_status_silent
            )
            Mode.DND -> dialog.setIconAndLabel(
                com.android.internal.R.drawable.ic_qs_dnd,
                R.string.alert_slider_mode_dnd_text
            )
        }
    }

    private fun showDialog(position: Position) {
        removeHandlerCalbacks()
        if (screenLifecycle.screenState == ScreenLifecycle.SCREEN_ON) {
            dialog.show(position)
            handler.postDelayed(dismissDialogRunnable, TIMEOUT)
        }
    }

    private fun removeHandlerCalbacks() {
        if (handler.hasCallbacks(dismissDialogRunnable)) {
            handler.removeCallbacks(dismissDialogRunnable)
        }
    }
}
