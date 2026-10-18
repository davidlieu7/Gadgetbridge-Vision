/*  Copyright (C) 2026 The Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.canon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.getParcelableCompat

class CanonCameraControlActivity : AbstractGBActivity() {
    private lateinit var device: GBDevice
    private lateinit var shutterButton: Button
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var reconnectButton: Button

    private val deviceChangedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (GBDevice.ACTION_DEVICE_CHANGED != intent.action) {
                return
            }
            val changedDevice = intent.getParcelableCompat<GBDevice>(GBDevice.EXTRA_DEVICE) ?: return
            if (changedDevice != device) {
                return
            }
            device = changedDevice
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_canon_camera_control)

        device = intent.getParcelableCompat<GBDevice>(GBDevice.EXTRA_DEVICE)
            ?: throw IllegalArgumentException(GBDevice.EXTRA_DEVICE + " must not be null")

        shutterButton = findViewById(R.id.canon_camera_shutter_button)
        nameView = findViewById(R.id.canon_camera_name)
        statusView = findViewById(R.id.canon_camera_status)
        reconnectButton = findViewById(R.id.canon_camera_reconnect_button)

        shutterButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sendConfiguration(CanonCameraConstants.ACTION_CONTROL_SHUTTER_PRESS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendConfiguration(CanonCameraConstants.ACTION_CONTROL_SHUTTER_RELEASE)
                    true
                }
                else -> false
            }
        }

        reconnectButton.setOnClickListener {
            GBApplication.deviceService(device).connect()
        }

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(deviceChangedReceiver, IntentFilter(GBDevice.ACTION_DEVICE_CHANGED))

        updateUi()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(deviceChangedReceiver)
        super.onDestroy()
    }

    private fun updateUi() {
        nameView.text = device.name
        statusView.text = device.getStateString(this)
        shutterButton.isEnabled = device.isInitialized
        reconnectButton.visibility = if (device.isConnected) {
            View.INVISIBLE
        } else {
            View.VISIBLE
        }
    }

    private fun sendConfiguration(config: String) {
        GBApplication.deviceService(device).onSendConfiguration(config)
    }
}
