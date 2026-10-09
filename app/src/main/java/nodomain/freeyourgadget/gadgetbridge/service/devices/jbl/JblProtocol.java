/*  Copyright (C) 2026

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package nodomain.freeyourgadget.gadgetbridge.service.devices.jbl;

import android.os.ParcelUuid;

import java.util.UUID;

/**
 * Protocol constants and definitions for Harman / JBL Protocol 3 devices.
 */
public final class JblProtocol {
    private JblProtocol() {
    }

    public static final byte HEADER_MAGIC = (byte) 0xAA;
    public static final int TUNE_BUDS_2_PRODUCT_ID = 0x210c;

    // Harman service-data key carrying the PID/CRC advertisement. Shared by many JBL models,
    // so the PID inside it is what identifies the headset. ScanRecord.getServiceData() only
    // accepts ParcelUuid, so the key is kept in that type rather than parsed per lookup.
    public static final ParcelUuid HARMAN_SERVICE_DATA_UUID =
            ParcelUuid.fromString("0000fddf-0000-1000-8000-00805f9b34fb");

    // Harman BES BLE GATT Service and Characteristic UUIDs
    public static final UUID SERVICE_UUID               = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0000");
    public static final UUID NOTIFY_CHARACTERISTIC_UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0001");
    public static final UUID WRITE_CHARACTERISTIC_UUID  = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0002");
    public static final UUID RAW_CHARACTERISTIC_UUID    = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0003");

    // Standard Client Characteristic Configuration descriptor, written to subscribe.
    public static final UUID CCC_DESCRIPTOR_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Subcommand types
    public static final byte SUB_CMD_SET = 0x00;
    public static final byte SUB_CMD_GET = 0x01;
    public static final byte SUB_CMD_RET = 0x02;

    // Protocol Command Opcodes
    public static final byte CMD_DEVICE_CONFIG          = 0x06;
    public static final byte CMD_EAR_BEEPING            = 0x36;
    public static final byte CMD_BATTERY_STATUS         = 0x25;
    public static final byte CMD_EQ_SETTINGS_2          = (byte) 0xa2;
    public static final byte CMD_GESTURE_CONFIG         = 0x77;
    public static final byte CMD_SMART_AUDIO_VIDEO      = (byte) 0x80;
    public static final byte CMD_NOISE_CONTROL          = (byte) 0x91;
    public static final byte CMD_VOICE_PROMPTS          = (byte) 0x93;
    public static final byte CMD_SIDETONE               = (byte) 0x98;
    public static final byte CMD_SPATIAL_AUDIO          = (byte) 0x9D;
    public static final byte CMD_SAFE_SOUND             = (byte) 0xA5;
    public static final byte CMD_STEREO_BALANCE         = (byte) 0xA8;

    // Subcommands for Noise Control (0x91)
    public static final byte SUB_NOISE_CONTROL_SET_MODES     = 0x10;
    public static final byte SUB_NOISE_CONTROL_GET_MODES     = 0x11;
    public static final byte SUB_NOISE_CONTROL_RESP_MODES    = 0x12;
    public static final byte SUB_NOISE_CONTROL_SET_OFF       = 0x13;
    public static final byte SUB_NOISE_CONTROL_SET_ADVANCED  = 0x20;
    public static final byte SUB_NOISE_CONTROL_GET_ADVANCED  = 0x21;
    public static final byte SUB_NOISE_CONTROL_RESP_ADVANCED = 0x22;

    // Advanced ANC / Ambient Aware field types
    public static final byte FIELD_ADAPTIVE_ANC              = 0x01;
    public static final byte FIELD_MANUAL_ANC_LEVEL          = 0x04;
    public static final byte FIELD_AUTO_LEAKAGE_COMPENSATION = 0x08;
    public static final byte FIELD_AMBIENT_AWARE_LEVEL       = (byte) 0xA1;

    // Sound Mode Slots
    public static final byte SLOT_ANC                        = 0x01;
    public static final byte SLOT_AMBIENT                    = 0x02;
    public static final byte SLOT_TALKTHRU                   = 0x03;

    // Sound Mode State Values
    public static final int SOUND_MODE_OFF                   = 0;
    public static final int SOUND_MODE_ANC                   = 1;
    public static final int SOUND_MODE_TALKTHRU              = 2;
    public static final int SOUND_MODE_AMBIENT               = 3;

    // Device Configuration Feature IDs (0x06)
    public static final byte FEATURE_FIRMWARE_VERSION       = 0x05;
    public static final byte FEATURE_CHARGING_CASE_FIRMWARE = 0x09;
    public static final byte FEATURE_AUTO_OFF                = 0x0b;
    public static final byte FEATURE_SPATIAL_SOUND           = 0x0c;
    public static final byte FEATURE_VOICE_AWARE_SIDE_TONE   = 0x0d;
    public static final byte FEATURE_CALL_SOUND_EFFECT       = 0x14;
    public static final byte FEATURE_BATTERY_LEFT             = 0x1a;
    public static final byte FEATURE_BATTERY_RIGHT            = 0x1b;
    public static final byte FEATURE_BATTERY_CASE             = 0x1c;
    public static final byte FEATURE_SAFE_SOUND               = 0x20;
    public static final byte FEATURE_CALL_VOLUME_OPTIMIZER   = 0x23;

    // Spatial Audio Types
    public static final int SPATIAL_AUDIO_OFF                = 0;
    public static final int SPATIAL_AUDIO_MUSIC              = 1;
    public static final int SPATIAL_AUDIO_MOVIE              = 2;
    public static final int SPATIAL_AUDIO_GAME               = 3;

    // Latency Modes
    public static final int LATENCY_MODE_AUDIO               = 0;
    public static final int LATENCY_MODE_VIDEO               = 1;

    public static final int CALL_SOUND_EFFECT_NATURAL        = 0;
    public static final int CALL_SOUND_EFFECT_TREBLE_BOOST    = 1;
    public static final int CALL_SOUND_EFFECT_BASS_BOOST      = 2;

    // Custom Parametric Equalizer Parameters
    public static final int CUSTOM_EQ_BAND_COUNT             = 10;
    public static final int EQ_CATEGORY_CUSTOMIZED           = 230;
    public static final byte EQ_SET_Q_MULTIPLY_10            = 0x03;
    public static final byte EQ_RET_Q_MULTIPLY_10            = 0x04;
    public static final int EQ_SETTINGS_HEADER_LENGTH        = 16;
    public static final int EQ_BAND_LENGTH                   = 10;

    public static final int[] CUSTOM_EQ_FREQUENCIES = {
            32, 64, 125, 250, 500,
            1000, 2000, 4000, 8000, 16000
    };

    // Touch Gesture Slots
    public static final byte GESTURE_SLOT_LEFT_SINGLE_TAP    = 6;
    public static final byte GESTURE_SLOT_LEFT_HOLD          = 12;
    public static final byte GESTURE_SLOT_RIGHT_SINGLE_TAP   = 9;
    public static final byte GESTURE_SLOT_RIGHT_HOLD         = 14;

    // Touch Gesture Actions
    public static final byte GESTURE_ACTION_NONE             = 0;
    public static final byte GESTURE_ACTION_VOLUME_UP        = 1;
    public static final byte GESTURE_ACTION_VOLUME_DOWN      = 2;
    public static final byte GESTURE_ACTION_TALKTHRU         = 4;
    public static final byte GESTURE_ACTION_NEXT_TRACK       = 5;
    public static final byte GESTURE_ACTION_PREV_TRACK       = 6;
    public static final byte GESTURE_ACTION_PLAY_PAUSE       = 8;
    public static final byte GESTURE_ACTION_ANC_AMBIENT_AWARE_OFF = 9;
    public static final byte GESTURE_ACTION_ANC_AMBIENT = 11;
    public static final byte GESTURE_ACTION_ANC_OFF = 12;
    public static final byte GESTURE_ACTION_AMBIENT_AWARE_OFF = 13;
    public static final byte GESTURE_ACTION_TALK_TO_DEFAULT_ASSISTANT = (byte) 0xA1;

    // Gesture Roles
    public static final int GESTURE_ROLE_OFF                 = 0;
    public static final int GESTURE_ROLE_AMBIENT_SOUND_CONTROL = 1;
    public static final int GESTURE_ROLE_PLAYBACK_CONTROL    = 2;
    public static final int GESTURE_ROLE_VOLUME_CONTROL      = 3;
    public static final int GESTURE_ROLE_VOICE_ASSISTANT     = 4;

    // Voice Prompts & Tones. The device only knows enabled/disabled.
    public static final int VOICE_PROMPT_LANGUAGE            = 1;

    // Ear Beeping (Find My Buds) Actions
    public static final byte BEEP_STOP_RIGHT                 = 0x00;
    public static final byte BEEP_STOP_LEFT                  = 0x01;
    public static final byte BEEP_START_RIGHT                = 0x10;
    public static final byte BEEP_START_LEFT                 = 0x11;

    // Stereo Balance Base Offset
    public static final int BALANCE_CENTER_OFFSET            = 100;
}
