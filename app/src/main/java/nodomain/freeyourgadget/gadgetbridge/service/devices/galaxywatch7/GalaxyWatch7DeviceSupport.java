/*  Copyright (C) 2026 pwnie69

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.galaxywatch7;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.AbstractBTBRDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Device support for the Samsung Galaxy Watch7 over the reverse-engineered Wear OS "Data Layer"
 * RFCOMM transport (see {@link WearDataLayerProtocol}). Establishes the node handshake/peer-sync,
 * then drives the features wired up so far as the watch's sole companion (no Galaxy Wearable /
 * watch7plugin needed): battery reporting, reading + writing watch settings, and forwarding phone
 * notifications to the watch's notification stream. See the project's research notes for the full
 * protocol writeup and the remaining planned features (media control, quick replies, ...).
 */
public class GalaxyWatch7DeviceSupport extends AbstractBTBRDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(GalaxyWatch7DeviceSupport.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String PREF_LOCAL_NODE_ID = "galaxywatch7_local_node_id";

    // EXPERIMENT (§16): set to a captured enrolled GMS Wear node id to impersonate it; null uses
    // GB's own persisted random id. Impersonation alone does NOT get past the watch's Fast Pair
    // GATT secret handshake (§17), so left disabled.
    private static final String EXPERIMENT_IMPERSONATE_NODE_ID = "e1b1cd8b";

    /**
     * This phone's own Data Layer node id, persisted per paired device so it stays STABLE across
     * reconnects (a peer that changes identity on every connect looks abnormal to the watch).
     * Initialised lazily in {@link #initializeDevice} from the per-device prefs.
     */
    private volatile String localNodeId;

    /** This node's field-3 nonce; value isn't validated by the watch, only its presence. */
    private final long nodeEpoch = RANDOM.nextLong() & 0x7fffffffffffffffL;

    public GalaxyWatch7DeviceSupport() {
        super(LOG, 4096);
    }

    private boolean proxyProbeStarted = false;

    @Override
    public boolean connect() {
        if (!proxyProbeStarted) {
            proxyProbeStarted = true;
            startBackChannelListeners();
        }
        return super.connect();
    }

    @Override
    protected UUID getSupportedService() {
        return WearDataLayerProtocol.SERVICE_UUID_DATA_LAYER;
    }

    @Override
    protected int getExtraConnectRetries() {
        // The secure socket variant's connect() consistently fails with a spurious "read
        // failed, socket might closed or timeout, read ret: -1" IOException against this
        // device, on both a near-instant and a genuine ~3s-then-explicit-failure timing
        // pattern (see research notes) - possibly transient/flaky at the native BT stack
        // level. Retrying immediately (recreating the socket each time) a few times is much
        // cheaper than waiting for Gadgetbridge's own multi-second auto-reconnect backoff,
        // and costs nothing if it doesn't help.
        return 5;
    }

    @Override
    protected int getConnectRetryDelayMillis() {
        return 400;
    }

    @Override
    protected int getFastReconnectBudget() {
        // The watch consistently sends its own NodeInfo reply and then closes the channel
        // again within ~10-100ms - observed in raw HCI captures to be a deliberate,
        // near-instant disconnect from the watch's side, not a timeout. Real captured
        // sessions (stock app) show several such quick reconnect cycles before "settling"
        // into a stable, long-lived session - testing whether reconnecting fast (rather
        // than waiting Gadgetbridge's multi-second external backoff) reproduces that.
        return 10;
    }

    @Override
    protected int getFastReconnectWindowMillis() {
        return 1000;
    }

    @Override
    protected int getConnectDelayMillis() {
        // Empirically, connecting the RFCOMM socket right after the ACL link comes up (which
        // needs a fresh authentication + encryption + SDP + RFCOMM-multiplexer round trip,
        // observed to take on the order of 100-150ms) reliably fails with "read failed, socket
        // might closed or timeout, read ret: -1" from BluetoothSocket.connect() — before the
        // SABM for our channel is even sent. A short delay avoids the race.
        return 500;
    }

    // Per-device pref key under which we cache the watch's learned Data Layer node id, so that on
    // reconnects we can send the peer-sync straight after our NodeInfo (as the stock app does)
    // instead of having to wait for the watch's NodeInfo reply first.
    private static final String PREF_WATCH_NODE_ID = "galaxywatch7_watch_node_id";
    // Per-device cache of the shared connection long-id (NodeInfo field 7) the watch expects us
    // to echo. Learned from the watch's NodeInfo reply; without it the watch drops us right after
    // the handshake. Stable across factory resets (it is a watch-side identifier).
    private static final String PREF_WATCH_CONNECTION_ID = "galaxywatch7_watch_connection_id";

    // The watch's current Data Layer node id, learned by parsing its NodeInfo reply (falls back to
    // the cached value on reconnect). Changes across factory resets / re-pairings.
    private volatile String watchNodeId;
    // The shared connection long-id (NodeInfo field 7) we must echo back to the watch.
    private volatile String watchConnectionId;
    // Whether we've already sent the peer-sync for the current session (avoids sending it twice
    // when we both send it eagerly on connect and would otherwise resend on the NodeInfo reply).
    private volatile boolean peerSyncSent;

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        peerSyncSent = false;
        synchronized (incomingLock) {
            incoming = new byte[0];
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        // EXPERIMENT (§16): impersonate the enrolled GMS companion node id. Trust looks node-id
        // based (no crypto challenge on the wire), so presenting the id the watch already trusts
        // is what should make it sustain the session instead of dropping us as an unknown peer.
        localNodeId = EXPERIMENT_IMPERSONATE_NODE_ID != null ? EXPERIMENT_IMPERSONATE_NODE_ID : localNodeId;
        if (localNodeId == null) {
            String stored = prefs == null ? null : prefs.getString(PREF_LOCAL_NODE_ID, null);
            if (stored == null) {
                stored = randomNodeId();
                if (prefs != null) {
                    prefs.edit().putString(PREF_LOCAL_NODE_ID, stored).apply();
                }
            }
            localNodeId = stored;
        }
        if (watchNodeId == null && prefs != null) {
            watchNodeId = prefs.getString(PREF_WATCH_NODE_ID, null);
        }
        if (watchConnectionId == null && prefs != null) {
            watchConnectionId = prefs.getString(PREF_WATCH_CONNECTION_ID, null);
        }

        LOG.info("Galaxy Watch7: Data Layer channel connected, sending node handshake as {}", localNodeId);

        final String localName = "Gadgetbridge";
        if (watchConnectionId == null) {
            LOG.info("Galaxy Watch7: connection id not known yet - handshake will be rejected, "
                    + "learning it from the watch's reply for the next connect");
        }
        builder.write(WearDataLayerProtocol.buildNodeInfoHandshake(
                localNodeId, localName, nodeEpoch, watchConnectionId));

        // EXPERIMENT (§16): send the peer-sync eagerly (bundled after NodeInfo) when we already
        // know the watch's node id, so it reaches the watch within ~ms instead of via the slower
        // reply-driven queue path - the watch drops us ~40ms in, likely before a queued peer-sync
        // arrives. onSocketRead still covers the first-ever connect (no cached watch id yet).
        if (watchNodeId != null) {
            builder.run(() -> LOG.info("Galaxy Watch7: sending peer-sync eagerly for watch node {}", watchNodeId));
            builder.write(WearDataLayerProtocol.buildSeqAck(localNodeId, watchNodeId));
            peerSyncSent = true;
        }
        builder.setDeviceState(GBDevice.State.INITIALIZED);

        return builder;
    }

    // The watch validates a setting DataItem's source package + signing-cert digest against its
    // trusted_packages. Use the stock companion's identity so the write is accepted.
    private static final String SETTINGS_SOURCE_PACKAGE = "com.samsung.wearable.watch7plugin";
    private static final String SETTINGS_SOURCE_SIGNATURE = "9ca5170f381919dfe0446fcdab18b19a143b3163";
    // The phone-side node the watch's settings service actually reads protogensettings from.
    // NOT the connection node (e1b1cd8b): the stock client connects as e1b1cd8b but authors
    // settings under efb3c185 (reference capture §23/§24). Writing under the connection node only
    // relays the DataItem; the watch applies settings written under this node.
    private static final String SETTINGS_NODE = "efb3c185";

    // Boolean settings mapped to the Samsung w_module_sec_watch_setting sub-path the watch's
    // StDataLayerListenerService actually applies (research notes §28), decoded from
    // StDataLayerDisplayManager. Written under the connection node (localNodeId) with a
    // {booleanIsChecked, from:"mobile"} DataMap.
    private static final java.util.Map<String, String> BOOLEAN_SETTINGS = new java.util.HashMap<>();
    static {
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_advanced_action_button_siren_enabled", "advanced/action_button/siren/enabled");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_advanced_gestures_dismiss_alerts_and_reject_calls", "advanced/gestures/dismiss_alerts_and_reject_calls");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_advanced_gestures_quick_action", "advanced/gestures/quick_action");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_advanced_gestures_quick_action_watchface_second_action_enabled", "advanced/gestures/quick_action/watchface/second_action_enabled");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_advanced_gestures_raise_to_talk_enabled", "advanced/gestures/raise_to_talk/enabled");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_advanced_labs_quicklaunch_enabled", "advanced/labs/quicklaunch/enabled");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_advanced_media_control_auto_launch_media_controls", "advanced/media_control/auto_launch_media_controls");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_advanced_media_control_show_ongoing_icon", "advanced/media_control/show_ongoing_icon");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_bedtime_turn_on_scheduled", "bedtime/turn_on_scheduled");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_display_always_on_display", "display/always_on_display");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_display_auto_low_brightness", "display/auto_low_brightness");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_display_bold_font", "display/bold_font");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_display_show_charging_info", "display/show_charging_info");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_display_touch_sensitivity", "display/touch_sensitivity");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_display_wake_on_touch_screen", "display/wake_on_touch_screen");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_display_wake_on_turning_bezel", "display/wake_on_turning_bezel");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_display_wake_on_wrist_raised", "display/wake_on_wrist_raised");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_general_touch_bezel", "general/touch_bezel");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_general_touch_bezel_feedback", "general/touch_bezel_feedback");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_settings_system_sounds_charging_sound", "sound_settings/system_sounds/charging_sound");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_settings_system_sounds_dialing_keypad_tone", "sound_settings/system_sounds/dialing_keypad_tone");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_settings_system_sounds_hourly_chime", "sound_settings/system_sounds/hourly_chime");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_settings_system_sounds_samsung_keyboard", "sound_settings/system_sounds/samsung_keyboard");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_settings_system_sounds_ticking_sound", "sound_settings/system_sounds/ticking_sound");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_settings_system_sounds_touch_sound", "sound_settings/system_sounds/touch_sound");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_settings_system_sounds_unlock_sound", "sound_settings/system_sounds/unlock_sound");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_settings_system_vibration_charging", "sound_settings/system_vibration/charging");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_settings_system_vibration_dialing_keypad", "sound_settings/system_vibration/dialing_keypad");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_settings_system_vibration_samsung_keyboard", "sound_settings/system_vibration/samsung_keyboard");
        // Additional toggles from the settings catalog (§5h). Any whose path the watch does not
        // actually publish simply never reflects/applies; harmless.
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_advanced_bixby_enabled", "advanced/bixby/enabled");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_display_outdoor_mode", "display/outdoor_mode");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_vibration_feedback", "sound_settings/vibration_feedback");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_sound_vibration_with_sound", "sound_settings/vibration_with_sound");
        BOOLEAN_SETTINGS.put("pref_galaxywatch7_phone_dnd_sync", "phone/dnd/sync_do_not_disturb/enabled");
    }

    // Non-boolean settings: a slider (INT) or a list. Each maps a GB preference key to the watch
    // sub-path, the DataMap value key the watch's manager reads, and how the pref stores the value.
    // INT = SeekBarPreference storing an int (key "intLevel"); STRING = ListPreference storing the
    // watch's string value (e.g. sound_mode "stringMode"); INT_LIST = ListPreference whose entry
    // values are numeric strings written as an int index (e.g. vibration_intensity "intSelectedItem").
    // Only settings whose watch-side handleSyncData actually applies the incoming value are wired -
    // font_style and securitysetting/wrist_detection are echo-only on the watch and omitted on purpose.
    // LIST_DYNAMIC = a ListPreference whose options are NOT fixed but pushed by the watch as a
    // "<base>/list" DataItem (DataMap key "arrayItemList", a string array of labels in index order);
    // the selection is a "<base>/selected_item" int (key "intSelectedItem"). We cache the labels and
    // populate the ListPreference at runtime (see GalaxyWatch7SettingsCustomizer), reflect the
    // selected index, and write "<base>/selected_item" on change.
    enum SettingType { INT, STRING, INT_LIST, LIST_DYNAMIC }

    static final class SettingSpec {
        final String prefKey;
        final String subPath;
        final String valueKey;
        final SettingType type;
        // For LIST_DYNAMIC: the sub-path of the option-list DataItem (usually subPath + "/list", but
        // e.g. ringtone's list lives at "sound_settings/ringtone/name/list"). Null otherwise.
        final String listPath;

        SettingSpec(final String prefKey, final String subPath, final String valueKey, final SettingType type) {
            this(prefKey, subPath, valueKey, type, type == SettingType.LIST_DYNAMIC ? subPath + "/list" : null);
        }

        SettingSpec(final String prefKey, final String subPath, final String valueKey,
                    final SettingType type, final String listPath) {
            this.prefKey = prefKey;
            this.subPath = subPath;
            this.valueKey = valueKey;
            this.type = type;
            this.listPath = listPath;
        }
    }

    // Suffix on the device pref under which we cache a LIST_DYNAMIC setting's option labels
    // (newline-joined), so the settings screen can populate the ListPreference from the last values
    // the watch pushed. Public so the settings customizer reads the same key.
    public static final String DYNLIST_ENTRIES_SUFFIX = "_gw7_entries";

    private static final java.util.Map<String, SettingSpec> VALUE_SETTINGS = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, SettingSpec> VALUE_SETTINGS_BY_PATH = new java.util.HashMap<>();
    // LIST_DYNAMIC only: option-list sub-path ("<base>/list") -> spec, to catch the list DataItem.
    private static final java.util.Map<String, SettingSpec> VALUE_SETTINGS_BY_LIST_PATH = new java.util.HashMap<>();
    static final java.util.List<SettingSpec> DYNAMIC_LIST_SETTINGS = new java.util.ArrayList<>();
    static {
        addValueSetting(new SettingSpec("pref_galaxywatch7_display_brightness", "display/brightness", "intLevel", SettingType.INT));
        addValueSetting(new SettingSpec("pref_galaxywatch7_display_font_size", "display/font_size", "intLevel", SettingType.INT));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_volume_media", "sound_settings/volume/media", "intLevel", SettingType.INT));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_volume_notifications", "sound_settings/volume/notifications", "intLevel", SettingType.INT));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_volume_ringtone", "sound_settings/volume/ringtone", "intLevel", SettingType.INT));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_volume_system", "sound_settings/volume/system", "intLevel", SettingType.INT));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_volume_accessibility", "sound_settings/volume/accessibility", "intLevel", SettingType.INT));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_volume_bixby", "sound_settings/volume/bixby", "intLevel", SettingType.INT));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_mode", "sound_settings/sound_mode", "stringMode", SettingType.STRING));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_vibration_intensity", "sound_settings/vibration_intensity", "intSelectedItem", SettingType.INT_LIST));
        // Dynamic lists (options pushed by the watch).
        addValueSetting(new SettingSpec("pref_galaxywatch7_display_screen_timeout", "display/screen_time_out", "intSelectedItem", SettingType.LIST_DYNAMIC));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_notification_sound", "sound_settings/notification_sound", "intSelectedItem", SettingType.LIST_DYNAMIC));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_notification_vibration", "sound_settings/notification_vibration", "intSelectedItem", SettingType.LIST_DYNAMIC));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_ringtone", "sound_settings/ringtone", "intSelectedItem", SettingType.LIST_DYNAMIC, "sound_settings/ringtone/name/list"));
        addValueSetting(new SettingSpec("pref_galaxywatch7_sound_ringtone_vibration", "sound_settings/ringtone_vibration", "intSelectedItem", SettingType.LIST_DYNAMIC));
        // Buttons & gestures: press_and_hold selects by index (arrayItemList + selected_item), same
        // proven mechanism as above, and its options are clean localized labels. The other pickers
        // are omitted on purpose: action_button/status_indicator list raw component names / internal
        // tokens ("com.samsung.../...Service", "show"/"hide2") the watch doesn't localize inline, and
        // double_press/digital_assistant/quicklaunch select by a string (selected_package/
        // selected_name/launch_package) with a not-yet-confirmed write key. Deferred (see docs/§).
        addValueSetting(new SettingSpec("pref_galaxywatch7_advanced_press_and_hold", "advanced/press_and_hold", "intSelectedItem", SettingType.LIST_DYNAMIC));
    }

    private static void addValueSetting(final SettingSpec spec) {
        VALUE_SETTINGS.put(spec.prefKey, spec);
        VALUE_SETTINGS_BY_PATH.put(spec.subPath, spec);
        if (spec.type == SettingType.LIST_DYNAMIC) {
            VALUE_SETTINGS_BY_LIST_PATH.put(spec.listPath, spec);
            DYNAMIC_LIST_SETTINGS.add(spec);
        }
    }

    /** GB preference keys of the dynamic-list settings (options pushed by the watch). For the
     *  settings customizer, which lives in a different package. */
    public static String[] dynamicListPrefKeys() {
        final String[] keys = new String[DYNAMIC_LIST_SETTINGS.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = DYNAMIC_LIST_SETTINGS.get(i).prefKey;
        }
        return keys;
    }
    // Reverse map: the protogensettings "<group>/<key>" -> GB preference key, to reflect the
    // watch's reported values back into the device prefs so the UI shows the real state.
    private static final java.util.Map<String, String> BOOLEAN_SETTINGS_BY_PATH = new java.util.HashMap<>();
    static {
        for (final java.util.Map.Entry<String, String> e : BOOLEAN_SETTINGS.entrySet()) {
            BOOLEAN_SETTINGS_BY_PATH.put(e.getValue(), e.getKey());
        }
    }

    // Highest DataItem sequence seen from the WATCH's nodes (used only to observe the peer, not for
    // our own writes - the watch tracks each node's data version separately).
    private final java.util.concurrent.atomic.AtomicLong settingsSeq = new java.util.concurrent.atomic.AtomicLong(0);

    // Version counter for DataItems WE host (under our node). The watch tracks our node's data
    // version separately and IGNORES a DataItem whose seq is not greater than the counter it already
    // holds for our node. That counter is not the watch's own node seq (what settingsSeq tracks) - it
    // is reported per node in the watch's peer-sync, so we seed this from parsePeerSyncNodeCounter for
    // our node id and increment above it. Without this, writes look stale and are silently dropped.
    private final java.util.concurrent.atomic.AtomicLong ownDataVersion = new java.util.concurrent.atomic.AtomicLong(0);

    @Override
    public void onSendConfiguration(final String config) {
        final String booleanSubPath = BOOLEAN_SETTINGS.get(config);
        final SettingSpec valueSpec = VALUE_SETTINGS.get(config);
        if (booleanSubPath == null && valueSpec == null) {
            return;
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        if (prefs == null) {
            return;
        }
        final String node = localNodeId;
        if (node == null) {
            LOG.warn("Galaxy Watch7: cannot send {} - own node id not known yet", config);
            return;
        }
        final long seq = ownDataVersion.incrementAndGet();
        // The watch's StDataLayerListenerService applies w_module_sec_watch_setting DataItems whose
        // host node is a connected peer (our connection node) and whose "from" != "watch" (§28).
        final byte[] write;
        final String subPath;
        if (booleanSubPath != null) {
            subPath = booleanSubPath;
            final boolean enabled = prefs.getBoolean(config, false);
            LOG.info("Galaxy Watch7: sending w_module {} = {} under node {} (seq {})", subPath, enabled, node, seq);
            write = WearDataLayerProtocol.buildWatchSettingBooleanPut(
                    SETTINGS_SOURCE_PACKAGE, SETTINGS_SOURCE_SIGNATURE, node, subPath, enabled, seq);
        } else {
            // A dynamic list is written to "<base>/selected_item"; everything else writes its subPath.
            subPath = valueSpec.type == SettingType.LIST_DYNAMIC
                    ? valueSpec.subPath + "/selected_item" : valueSpec.subPath;
            switch (valueSpec.type) {
                case STRING: {
                    final String value = prefs.getString(config, "");
                    LOG.info("Galaxy Watch7: sending w_module {} = \"{}\" under node {} (seq {})", subPath, value, node, seq);
                    write = WearDataLayerProtocol.buildWatchSettingStringPut(
                            SETTINGS_SOURCE_PACKAGE, SETTINGS_SOURCE_SIGNATURE, node, subPath, valueSpec.valueKey, value, seq);
                    break;
                }
                case INT_LIST:
                case LIST_DYNAMIC: {
                    // ListPreference stores the index as a numeric string; the watch reads an int.
                    final int value = parseIntPref(prefs.getString(config, "0"), 0);
                    LOG.info("Galaxy Watch7: sending w_module {} = {} under node {} (seq {})", subPath, value, node, seq);
                    write = WearDataLayerProtocol.buildWatchSettingIntPut(
                            SETTINGS_SOURCE_PACKAGE, SETTINGS_SOURCE_SIGNATURE, node, subPath, valueSpec.valueKey, value, seq);
                    break;
                }
                case INT:
                default: {
                    final int value = prefs.getInt(config, 0);
                    LOG.info("Galaxy Watch7: sending w_module {} = {} under node {} (seq {})", subPath, value, node, seq);
                    write = WearDataLayerProtocol.buildWatchSettingIntPut(
                            SETTINGS_SOURCE_PACKAGE, SETTINGS_SOURCE_SIGNATURE, node, subPath, valueSpec.valueKey, value, seq);
                    break;
                }
            }
        }
        recentlySentSetting.put(subPath, System.currentTimeMillis());
        final TransactionBuilder builder = createTransactionBuilder("send " + config);
        builder.write(write);
        builder.queue();
    }

    // Notifications we've pushed (id -> {packageName, tag}), so a later dismissal can rebuild the
    // exact /bridger/stream_item: path to DELETE (onDeleteNotification only gives us the id).
    private final java.util.Map<Integer, String[]> sentNotifications = new java.util.concurrent.ConcurrentHashMap<>();
    // Fallback accent color for notifications that don't carry one (Gadgetbridge blue).
    private static final long NOTIFICATION_DEFAULT_COLOR = 0xff2196f3L;

    @Override
    public void onNotification(final nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec spec) {
        final String node = localNodeId;
        if (node == null) {
            LOG.warn("Galaxy Watch7: cannot push notification - own node id not known yet");
            return;
        }
        final String pkg = spec.getSourceAppId() != null
                ? spec.getSourceAppId() : "nodomain.freeyourgadget.gadgetbridge";
        final String appName = spec.getSourceName() != null ? spec.getSourceName() : pkg;
        String title = spec.getTitle();
        if (title == null || title.isEmpty()) {
            title = spec.getSender() != null ? spec.getSender() : appName;
        }
        final String text = spec.getBody() != null ? spec.getBody() : "";
        final String tag = "null_tag";
        final long postTime = spec.getWhen() > 0 ? spec.getWhen() : System.currentTimeMillis();
        final long seq = ownDataVersion.incrementAndGet();
        final java.util.List<byte[]> wearableActions = buildWearableActions(spec);
        LOG.info("Galaxy Watch7: pushing notification id={} pkg={} title=\"{}\" replyActions={} (seq {})",
                spec.getId(), pkg, title, wearableActions.size(), seq);
        final byte[] write = WearDataLayerProtocol.buildNotificationPut(
                SETTINGS_SOURCE_PACKAGE, SETTINGS_SOURCE_SIGNATURE, node, pkg, spec.getId(), tag,
                appName, title, text, appName, postTime, postTime,
                NOTIFICATION_DEFAULT_COLOR, spec.getFlags(), spec.getCategory(), seq, wearableActions);
        sentNotifications.put(spec.getId(), new String[]{pkg, tag});
        final TransactionBuilder builder = createTransactionBuilder("notify " + spec.getId());
        builder.write(write);
        builder.queue();
    }

    // Quick-reply: encode each of a notification's reply actions as a wearable_action the watch shows
    // as a text-reply field. The action's GB handle is carried as the wearable action's intent_id, so
    // the reply the watch sends back on /bridger/rpc maps straight to a REPLY on that handle (§46).
    private static final String REPLY_RETURN_KEY = "gb_reply";
    // Handles we sent as reply actions - a bridged reply is only honoured for one of these.
    private final java.util.Set<Long> sentReplyHandles =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private java.util.List<byte[]> buildWearableActions(final nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec spec) {
        final java.util.List<byte[]> out = new java.util.ArrayList<>();
        if (spec.getAttachedActions() == null) {
            return out;
        }
        for (final nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec.Action action : spec.getAttachedActions()) {
            if (action == null || !action.isReply()) {
                continue;
            }
            final String title = action.getTitle() != null && !action.getTitle().isEmpty()
                    ? action.getTitle() : "Reply";
            sentReplyHandles.add(action.getHandle());
            out.add(WearDataLayerProtocol.buildWearableAction(
                    title, String.valueOf(action.getHandle()), true, REPLY_RETURN_KEY));
        }
        return out;
    }

    // Incoming-call display. The watch shows phone calls via the same Wear notification-stream
    // bridge as normal notifications (§34): we push a call-category notification when the phone
    // rings and delete it when the call ends/answers. A fixed id keeps it a single, updatable card.
    // Answering/rejecting FROM the watch (via Samsung's w_callservice / w_apps_incallui channel) is
    // a separate, not-yet-reversed protocol - for now this is a display-only incoming-call alert.
    private static final int CALL_NOTIFICATION_ID = 0x0CA11; // stable id for the call card
    private static final String CALL_PACKAGE = "com.android.server.telecom";

    @Override
    public void onSetCallState(final nodomain.freeyourgadget.gadgetbridge.model.CallSpec callSpec) {
        final String node = localNodeId;
        if (node == null) {
            return;
        }
        final String tag = "null_tag";
        switch (callSpec.getCommand()) {
            case nodomain.freeyourgadget.gadgetbridge.model.CallSpec.CALL_INCOMING: {
                final String caller = callSpec.getName() != null && !callSpec.getName().isEmpty()
                        ? callSpec.getName()
                        : (callSpec.getNumber() != null ? callSpec.getNumber() : "?");
                final long seq = ownDataVersion.incrementAndGet();
                final long now = System.currentTimeMillis();
                LOG.info("Galaxy Watch7: incoming call from \"{}\" (seq {})", caller, seq);
                final byte[] write = WearDataLayerProtocol.buildNotificationPut(
                        SETTINGS_SOURCE_PACKAGE, SETTINGS_SOURCE_SIGNATURE, node, CALL_PACKAGE,
                        CALL_NOTIFICATION_ID, tag, "Telefon", caller,
                        getContext().getString(nodomain.freeyourgadget.gadgetbridge.R.string.incoming_call),
                        null, now, now, NOTIFICATION_DEFAULT_COLOR, 0, "call", seq);
                sentNotifications.put(CALL_NOTIFICATION_ID, new String[]{CALL_PACKAGE, tag});
                final TransactionBuilder builder = createTransactionBuilder("call incoming");
                builder.write(write);
                builder.queue();
                break;
            }
            case nodomain.freeyourgadget.gadgetbridge.model.CallSpec.CALL_ACCEPT:
            case nodomain.freeyourgadget.gadgetbridge.model.CallSpec.CALL_REJECT:
            case nodomain.freeyourgadget.gadgetbridge.model.CallSpec.CALL_START:
            case nodomain.freeyourgadget.gadgetbridge.model.CallSpec.CALL_END: {
                if (sentNotifications.remove(CALL_NOTIFICATION_ID) == null) {
                    return; // no active call card to clear
                }
                final long seq = ownDataVersion.incrementAndGet();
                LOG.info("Galaxy Watch7: clearing incoming-call card (seq {})", seq);
                final byte[] write = WearDataLayerProtocol.buildNotificationDelete(
                        SETTINGS_SOURCE_PACKAGE, SETTINGS_SOURCE_SIGNATURE, node, CALL_PACKAGE,
                        CALL_NOTIFICATION_ID, tag, seq);
                final TransactionBuilder builder = createTransactionBuilder("call end");
                builder.write(write);
                builder.queue();
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onDeleteNotification(final int id) {
        final String node = localNodeId;
        final String[] info = sentNotifications.remove(id);
        if (node == null || info == null) {
            return; // never sent it (or not connected) - nothing to delete on the watch
        }
        final long seq = ownDataVersion.incrementAndGet();
        LOG.info("Galaxy Watch7: deleting notification id={} pkg={} (seq {})", id, info[0], seq);
        final byte[] write = WearDataLayerProtocol.buildNotificationDelete(
                SETTINGS_SOURCE_PACKAGE, SETTINGS_SOURCE_SIGNATURE, node, info[0], id, info[1], seq);
        final TransactionBuilder builder = createTransactionBuilder("del-notify " + id);
        builder.write(write);
        builder.queue();
    }

    // ---- Media control (MessageApi JSON channel /w_apps_media_controller/*, research notes §36/§40) ----
    // The watch drives media over MessageApi JSON (not only the adaptive_media_bridging RPC): it
    // asks the phone for state via request_data and shows what the phone pushes as mediametadata /
    // playbackstate / volume. We author these under the Samsung wcs.extension identity, same trick
    // as settings.
    private static final String MEDIA_SOURCE_PACKAGE = "com.samsung.android.wcs.extension";
    private static final String MEDIA_SOURCE_SIGNATURE = "9ca5170f381919dfe0446fcdab18b19a143b3163";
    // Find My Watch is registered on the watch under the watch7plugin package (same Samsung signing
    // cert as wcs.extension). Wear routes MessageEvents to the listener matching (package, signature),
    // so /w_apps_find_my_watch must be authored under THIS package or the watch never receives it.
    private static final String FMW_SOURCE_PACKAGE = "com.samsung.wearable.watch7plugin";
    private static final String MEDIA_PATH_PREFIX = "/w_apps_media_controller/";
    private final java.util.concurrent.atomic.AtomicLong messageSeq = new java.util.concurrent.atomic.AtomicLong(1);
    private volatile MusicSpec lastMusicSpec;
    private volatile MusicStateSpec lastMusicStateSpec;
    private volatile int lastPhoneVolume = 7; // 0-15 scale the watch uses

    @Override
    public void onSetMusicInfo(final MusicSpec musicSpec) {
        lastMusicSpec = musicSpec;
        sendMediaMetadata(musicSpec);
    }

    @Override
    public void onSetMusicState(final MusicStateSpec stateSpec) {
        lastMusicStateSpec = stateSpec;
        sendPlaybackState(stateSpec);
    }

    @Override
    public void onSetPhoneVolume(final float volume) {
        lastPhoneVolume = Math.round(volume / 100f * 15f); // GB gives 0-100%, watch wants 0-15
        sendVolume();
    }

    private void sendMediaMetadata(final MusicSpec spec) {
        if (spec == null) {
            return;
        }
        try {
            final JSONObject data = new JSONObject();
            data.put("appId", "nodomain.freeyourgadget.gadgetbridge");
            data.put("appTitle", "Gadgetbridge");
            data.put("mediaId", "");
            data.put("title", spec.getTrack() != null ? spec.getTrack() : "");
            data.put("artist", spec.getArtist() != null ? spec.getArtist() : "");
            data.put("album", spec.getAlbum() != null ? spec.getAlbum() : "");
            data.put("duration", spec.getDuration() > 0 ? (long) spec.getDuration() * 1000 : 0);
            data.put("albumArt", "");
            sendMediaMessage("mediametadata", data);
        } catch (final JSONException e) {
            LOG.warn("Galaxy Watch7: media metadata json", e);
        }
    }

    private void sendPlaybackState(final MusicStateSpec state) {
        if (state == null) {
            return;
        }
        // GB state 0=playing/1=paused/2=stopped -> Android PlaybackState 3=playing/2=paused/1=stopped.
        final int androidState = state.getState() == MusicStateSpec.STATE_PLAYING ? 3
                : state.getState() == MusicStateSpec.STATE_PAUSED ? 2 : 1;
        try {
            final JSONObject data = new JSONObject();
            data.put("appId", "nodomain.freeyourgadget.gadgetbridge");
            data.put("appTitle", "Gadgetbridge");
            data.put("state", androidState);
            data.put("position", state.getPosition() > 0 ? (long) state.getPosition() * 1000 : 0);
            data.put("speed", 1);
            // Android PlaybackState action bitmask so the watch knows the session is controllable
            // and shows transport buttons: STOP|PAUSE|PLAY|SKIP_NEXT|SKIP_PREV|PLAY_PAUSE = 1|2|4|32|16|512.
            data.put("actions", 1 | 2 | 4 | 16 | 32 | 512);
            sendMediaMessage("playbackstate", data);
        } catch (final JSONException e) {
            LOG.warn("Galaxy Watch7: playback state json", e);
        }
    }

    private void sendVolume() {
        try {
            final JSONObject data = new JSONObject();
            data.put("appId", "");
            data.put("playbackType", "1");
            data.put("volume", String.valueOf(lastPhoneVolume));
            data.put("maxVolume", "15");
            data.put("warningVolume", "10");
            data.put("headsetConnected", "0");
            data.put("btOutput", "0");
            data.put("dndModeOn", "0");
            data.put("muteAllSoundsOn", "0");
            sendMediaMessage("volume", data);
        } catch (final JSONException e) {
            LOG.warn("Galaxy Watch7: volume json", e);
        }
    }

    private void sendMediaMessage(final String msgId, final JSONObject data) {
        final String node = localNodeId;
        final String watch = watchNodeId;
        if (node == null || watch == null) {
            return;
        }
        try {
            final JSONObject env = new JSONObject();
            env.put("msgId", msgId);
            env.put("msgType", "4"); // 4 = data push from the phone
            env.put("deviceId", node);
            env.put("data", data);
            final byte[] write = WearDataLayerProtocol.buildMessageEvent(
                    MEDIA_SOURCE_PACKAGE, MEDIA_SOURCE_SIGNATURE, node, watch,
                    messageSeq.getAndIncrement(), MEDIA_PATH_PREFIX + msgId,
                    env.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            LOG.info("Galaxy Watch7: media -> {} {}", msgId, data);
            final TransactionBuilder builder = createTransactionBuilder("media " + msgId);
            builder.write(write);
            builder.queue();
        } catch (final JSONException e) {
            LOG.warn("Galaxy Watch7: media envelope json", e);
        }
    }

    // Handle an incoming media MessageEvent from the watch: answer its request_data, and turn its
    // transport button presses into Gadgetbridge music-control events.
    private void handleMediaMessage(final byte[] message) {
        final String path = WearDataLayerProtocol.parseMessageEventPath(message);
        if (path == null || !path.startsWith(MEDIA_PATH_PREFIX)) {
            return;
        }
        final String msgId = path.substring(MEDIA_PATH_PREFIX.length());
        final String json = WearDataLayerProtocol.parseMessageEventData(message);
        LOG.info("Galaxy Watch7: media <- {} {}", msgId, json);

        // Distinguish the watch's REQUESTS (msgType "0" with an empty appId, e.g. "force get media
        // meta and playback") from ACKs (msgType "0" echoing our own appId). Reply to requests with
        // our current state so the session stays live/controllable.
        String msgType = "";
        String appId = "";
        try {
            final JSONObject o = new JSONObject(json != null ? json : "{}");
            msgType = o.optString("msgType", "");
            final JSONObject d = o.optJSONObject("data");
            appId = d != null ? d.optString("appId", "") : "";
        } catch (final JSONException ignored) {
        }
        final boolean isRequest = "request_data".equals(msgId) || ("0".equals(msgType) && appId.isEmpty());
        if (isRequest) {
            switch (msgId) {
                case "request_data":
                    sendVolume();
                    if (lastMusicSpec != null) sendMediaMetadata(lastMusicSpec);
                    if (lastMusicStateSpec != null) sendPlaybackState(lastMusicStateSpec);
                    break;
                case "mediametadata":
                    if (lastMusicSpec != null) sendMediaMetadata(lastMusicSpec);
                    break;
                case "playbackstate":
                    if (lastMusicStateSpec != null) sendPlaybackState(lastMusicStateSpec);
                    break;
                case "volume":
                    sendVolume();
                    break;
                default:
                    break;
            }
            return;
        }
        final GBDeviceEventMusicControl.Event event = mediaCommandToEvent(msgId, json);
        if (event != null) {
            LOG.info("Galaxy Watch7: media command {} -> {}", msgId, event);
            final GBDeviceEventMusicControl evt = new GBDeviceEventMusicControl();
            evt.event = event;
            evaluateGBDeviceEvent(evt);
        }
    }

    // Map a watch media MessageApi msgId (+ its JSON) to a Gadgetbridge music-control event. The
    // exact command msgIds are confirmed live once the watch controls active media; this covers the
    // common ones and is easy to extend.
    private GBDeviceEventMusicControl.Event mediaCommandToEvent(final String msgId, final String json) {
        switch (msgId) {
            case "play":
                return GBDeviceEventMusicControl.Event.PLAY;
            case "pause":
                return GBDeviceEventMusicControl.Event.PAUSE;
            case "playpause":
            case "play_pause":
                return GBDeviceEventMusicControl.Event.PLAYPAUSE;
            case "next":
            case "skip_to_next":
                return GBDeviceEventMusicControl.Event.NEXT;
            case "previous":
            case "prev":
            case "skip_to_previous":
                return GBDeviceEventMusicControl.Event.PREVIOUS;
            default:
                return null;
        }
    }

    // Find My Watch: ring/stop the watch via a plain-JSON MessageEvent on /w_apps_find_my_watch.
    // Payload and transport verified against the stock com.samsung.wearable.watch7plugin
    // (FMWStartAlertReq/FMWStopAlertReq + FMWMessageSender: bare {"msg_id":...} on DATA_TRANSFER_PATH,
    // no connection_type, no handshake). Authored under the watch7plugin identity so the watch's
    // find_my_watch listener actually receives it (research notes §45).
    @Override
    public void onFindDevice(final boolean start) {
        final String node = localNodeId;
        final String watch = watchNodeId;
        if (node == null || watch == null) {
            return;
        }
        final String json = start ? "{\"msg_id\":\"fmw-startalert-req\"}" : "{\"msg_id\":\"fmw-stopalert-req\"}";
        LOG.info("Galaxy Watch7: find device start={} -> /w_apps_find_my_watch {}", start, json);
        final byte[] write = WearDataLayerProtocol.buildMessageEvent(
                FMW_SOURCE_PACKAGE, MEDIA_SOURCE_SIGNATURE, node, watch,
                messageSeq.getAndIncrement(), "/w_apps_find_my_watch",
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final TransactionBuilder builder = createTransactionBuilder("find device " + start);
        builder.write(write);
        builder.queue();
    }

    private static int parseIntPref(final String s, final int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    // group/key -> time we last sent it, so the watch's still-streaming old value does not revert
    // a just-toggled setting in the UI before the watch applies our write.
    private final java.util.Map<String, Long> recentlySentSetting = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SETTING_WRITE_SETTLE_MS = 8000;

    // Reassembly buffer for the incoming Data Layer stream. Socket reads do not align to message
    // boundaries, so we accumulate and split on the 4-byte big-endian length prefix.
    private final Object incomingLock = new Object();
    private byte[] incoming = new byte[0];
    private static final int MAX_FRAME_LEN = 1 << 20;

    @Override
    public void onSocketRead(final byte[] data) {
        synchronized (incomingLock) {
            byte[] merged = new byte[incoming.length + data.length];
            System.arraycopy(incoming, 0, merged, 0, incoming.length);
            System.arraycopy(data, 0, merged, incoming.length, data.length);

            int off = 0;
            while (merged.length - off >= 4) {
                final int len = ((merged[off] & 0xff) << 24) | ((merged[off + 1] & 0xff) << 16)
                        | ((merged[off + 2] & 0xff) << 8) | (merged[off + 3] & 0xff);
                if (len < 0 || len > MAX_FRAME_LEN) {
                    LOG.warn("Galaxy Watch7: bad frame length {}, dropping {} buffered bytes",
                            len, merged.length - off);
                    off = merged.length;
                    break;
                }
                if (merged.length - off - 4 < len) {
                    break; // incomplete message, wait for more bytes
                }
                final byte[] message = java.util.Arrays.copyOfRange(merged, off + 4, off + 4 + len);
                off += 4 + len;
                processIncomingMessage(message);
            }
            incoming = java.util.Arrays.copyOfRange(merged, off, merged.length);
        }
    }

    private void processIncomingMessage(final byte[] message) {
        LOG.info("Galaxy Watch7 <<< {}", WearDataLayerProtocol.describeMessage(message));

        trackSettingsSeq(message);
        seedOwnDataVersion(message);
        reflectIncomingSetting(message);
        reflectBattery(message);
        reflectNotificationDismissal(message);
        handleBridgedAction(message);
        handleMediaMessage(message);

        final String parsed = WearDataLayerProtocol.parseWatchNodeId(message);
        if (parsed == null) {
            return;
        }

        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        if (!parsed.equals(watchNodeId)) {
            LOG.info("Galaxy Watch7: learned watch node id {}", parsed);
            watchNodeId = parsed;
            if (prefs != null) {
                prefs.edit().putString(PREF_WATCH_NODE_ID, parsed).apply();
            }
        }

        final String connId = WearDataLayerProtocol.parseWatchConnectionId(message);
        if (connId != null && !connId.equals(watchConnectionId)) {
            LOG.info("Galaxy Watch7: learned connection id {} (used on next connect)", connId);
            watchConnectionId = connId;
            if (prefs != null) {
                prefs.edit().putString(PREF_WATCH_CONNECTION_ID, connId).apply();
            }
        }

        // First-connect path: we couldn't send the peer-sync until we learned the watch's node id.
        if (!peerSyncSent) {
            peerSyncSent = true;
            LOG.info("Galaxy Watch7: sending peer-sync in response to NodeInfo reply from {}", parsed);
            final TransactionBuilder builder = createTransactionBuilder("peer-sync");
            builder.write(WearDataLayerProtocol.buildSeqAck(localNodeId, parsed));
            builder.queue();
        }

    }

    // Keep our outgoing settings seq above the settings node's current seq, learned from the
    // incoming stream, so the watch does not reject our writes as stale.
    // node id -> highest DataItem seq we've seen for it, learned from the incoming stream. Used to
    // build a fuller peer-sync (like the stock client) that declares all nodes with their counters.
    private final java.util.concurrent.ConcurrentHashMap<String, Long> nodeCounters = new java.util.concurrent.ConcurrentHashMap<>();

    // When a notification is dismissed on the watch, the watch mirrors the DataItem delete back to
    // us; dismiss it on the phone too so the two stay in sync. Only act on ids we still consider
    // active (also avoids reacting to the echo of our own delete, which already removed the id).
    // Quick reply from the watch: the watch invokes a reply action by sending a /bridger/rpc
    // bridged_action MessageEvent carrying the action's intent_id (our GB handle) and the typed text.
    // Turn it into a GBDeviceEventNotificationControl REPLY on that handle and ack on /bridger/result.
    private void handleBridgedAction(final byte[] message) {
        final WearDataLayerProtocol.BridgedAction action = WearDataLayerProtocol.parseBridgedAction(message);
        if (action == null) {
            return;
        }
        long handle;
        try {
            handle = Long.parseLong(action.intentId);
        } catch (final NumberFormatException e) {
            LOG.warn("Galaxy Watch7: bridged action with non-numeric intent_id {}", action.intentId);
            return;
        }
        if (action.reply != null && sentReplyHandles.contains(handle)) {
            LOG.info("Galaxy Watch7: quick reply on handle {} : \"{}\"", handle, action.reply);
            final nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl evt =
                    new nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl();
            evt.handle = handle;
            evt.reply = action.reply;
            evt.event = nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl.Event.REPLY;
            evaluateGBDeviceEvent(evt);
        } else {
            LOG.info("Galaxy Watch7: bridged action on handle {} (reply={}, known={}) - not dispatched",
                    handle, action.reply != null, sentReplyHandles.contains(handle));
        }

        final String watch = watchNodeId;
        final String node = localNodeId;
        if (watch != null && node != null) {
            final byte[] ack = WearDataLayerProtocol.buildBridgedActionResult(
                    SETTINGS_SOURCE_PACKAGE, SETTINGS_SOURCE_SIGNATURE, node, watch,
                    messageSeq.getAndIncrement(), action.invocationId, true);
            final TransactionBuilder builder = createTransactionBuilder("bridged action ack");
            builder.write(ack);
            builder.queue();
        }
    }

    private void reflectNotificationDismissal(final byte[] message) {
        final Integer id = WearDataLayerProtocol.parseNotificationStreamDeleteId(message);
        if (id == null || sentNotifications.remove(id) == null) {
            return;
        }
        LOG.info("Galaxy Watch7: notification {} dismissed on watch, dismissing on phone", id);
        final nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl evt =
                new nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl();
        evt.handle = id;
        evt.event = nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl.Event.DISMISS;
        evaluateGBDeviceEvent(evt);
    }

    private int lastBatteryLevel = -1;
    // Timestamp (field 7 of the batterystate value) of the newest reading we've accepted. The watch
    // keeps a stale batterystate under an old node id alongside the live one; both arrive on connect,
    // so we must take the newest by timestamp, not whichever lands last (else the stale, days-old
    // level overwrites the correct one - the "battery stuck at 97%" bug).
    private long lastBatteryTimestamp = -1;

    // Report the watch's battery level/state to Gadgetbridge from its batterystate DataItem.
    private void reflectBattery(final byte[] message) {
        final long[] bs = WearDataLayerProtocol.parseBatteryState(message);
        if (bs == null) {
            return;
        }
        final int level = (int) bs[0];
        final long timestamp = bs[2];
        // Ignore a reading older than the newest one we've taken (a stale node's cached value).
        if (timestamp >= 0 && lastBatteryTimestamp >= 0 && timestamp < lastBatteryTimestamp) {
            return;
        }
        if (level == lastBatteryLevel && timestamp <= lastBatteryTimestamp) {
            return;
        }
        lastBatteryLevel = level;
        if (timestamp >= 0) {
            lastBatteryTimestamp = timestamp;
        }
        final GBDeviceEventBatteryInfo info = new GBDeviceEventBatteryInfo();
        info.level = level;
        switch ((int) bs[1]) {
            case 5: // BATTERY_STATUS_FULL
                info.state = BatteryState.BATTERY_CHARGING_FULL;
                break;
            case 2: // BATTERY_STATUS_CHARGING
                info.state = BatteryState.BATTERY_CHARGING;
                break;
            default:
                info.state = level <= 15 ? BatteryState.BATTERY_LOW : BatteryState.BATTERY_NORMAL;
                break;
        }
        LOG.info("Galaxy Watch7: battery {}% state {}", info.level, info.state);
        handleGBDeviceEvent(info);
    }

    private void trackSettingsSeq(final byte[] message) {
        final String path = WearDataLayerProtocol.parseDataItemPath(message);
        if (path == null || !path.startsWith("wear://")) {
            return;
        }
        final Long seq = WearDataLayerProtocol.parseDataItemSeq(message);
        if (seq == null) {
            return;
        }
        final int slash = path.indexOf('/', "wear://".length());
        final String node = slash < 0 ? path.substring("wear://".length()) : path.substring("wear://".length(), slash);
        nodeCounters.merge(node, seq, Math::max);
        // Keep our outgoing seq above every node's current seq so writes are never seen as stale.
        settingsSeq.getAndUpdate(cur -> Math.max(cur, seq));
    }

    // The watch's peer-sync reports, per node, the data version it currently holds. For our own node
    // it is the bar our pushed DataItems must clear: the watch ignores a write whose seq is not above
    // it. Raise ownDataVersion to at least that counter so the next incrementAndGet lands above it.
    private void seedOwnDataVersion(final byte[] message) {
        if (localNodeId == null) {
            return;
        }
        final Long counter = WearDataLayerProtocol.parsePeerSyncNodeCounter(message, localNodeId);
        if (counter != null) {
            final long updated = ownDataVersion.getAndUpdate(cur -> Math.max(cur, counter));
            LOG.info("Galaxy Watch7: watch holds our node {} at data version {} (own seq base now {})",
                    localNodeId, counter, Math.max(updated, counter));
        }
    }

    // Reflect a watch-reported protogensettings boolean back into the device prefs so the GB
    // settings UI shows the real current state. Only trust the watch's own node's values.
    private void reflectIncomingSetting(final byte[] message) {
        final String subPath = WearDataLayerProtocol.parseWatchSettingSubPath(message);
        if (subPath == null) {
            return;
        }
        final Long sentAt = recentlySentSetting.get(subPath);
        if (sentAt != null && System.currentTimeMillis() - sentAt < SETTING_WRITE_SETTLE_MS) {
            return; // we just wrote this; don't let the watch's echo revert the UI
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        if (prefs == null) {
            return;
        }

        final String booleanPrefKey = BOOLEAN_SETTINGS_BY_PATH.get(subPath);
        if (booleanPrefKey != null) {
            final Boolean value = WearDataLayerProtocol.parseDataMapBoolean(message, "booleanIsChecked");
            if (value != null && prefs.getBoolean(booleanPrefKey, false) != value) {
                LOG.info("Galaxy Watch7: reflecting {} = {} from watch", subPath, value);
                prefs.edit().putBoolean(booleanPrefKey, value).apply();
            }
            return;
        }

        // Dynamic list: the watch pushes the option set as "<base>/list" - cache the labels so the
        // settings screen can populate the ListPreference from the last values the watch reported.
        final SettingSpec listSpec = VALUE_SETTINGS_BY_LIST_PATH.get(subPath);
        if (listSpec != null) {
            final java.util.List<String> labels = WearDataLayerProtocol.parseDataMapStringArray(message, "arrayItemList");
            if (labels != null && !labels.isEmpty()) {
                final StringBuilder sb = new StringBuilder();
                for (final String l : labels) {
                    final int sep = l.indexOf("::"); // some entries are "value::label"
                    sb.append(sep >= 0 ? l.substring(sep + 2) : l).append('\n');
                }
                final String joined = sb.toString();
                if (!joined.equals(prefs.getString(listSpec.prefKey + DYNLIST_ENTRIES_SUFFIX, null))) {
                    LOG.info("Galaxy Watch7: cached {} options for {}", labels.size(), listSpec.subPath);
                    prefs.edit().putString(listSpec.prefKey + DYNLIST_ENTRIES_SUFFIX, joined).apply();
                }
            }
            return;
        }
        // Dynamic list selection: "<base>/selected_item" -> store the chosen index as the pref value.
        if (subPath.endsWith("/selected_item")) {
            final String base = subPath.substring(0, subPath.length() - "/selected_item".length());
            final SettingSpec selSpec = VALUE_SETTINGS_BY_PATH.get(base);
            if (selSpec != null && selSpec.type == SettingType.LIST_DYNAMIC) {
                final Integer value = WearDataLayerProtocol.parseDataMapInt(message, "intSelectedItem");
                if (value != null && !String.valueOf(value).equals(prefs.getString(selSpec.prefKey, null))) {
                    LOG.info("Galaxy Watch7: reflecting {} = {} from watch", subPath, value);
                    prefs.edit().putString(selSpec.prefKey, String.valueOf(value)).apply();
                }
                return;
            }
        }

        final SettingSpec spec = VALUE_SETTINGS_BY_PATH.get(subPath);
        if (spec == null) {
            return; // not a setting we expose
        }
        switch (spec.type) {
            case STRING: {
                final String value = WearDataLayerProtocol.parseDataMapString(message, spec.valueKey);
                if (value != null && !value.equals(prefs.getString(spec.prefKey, null))) {
                    LOG.info("Galaxy Watch7: reflecting {} = \"{}\" from watch", subPath, value);
                    prefs.edit().putString(spec.prefKey, value).apply();
                }
                break;
            }
            case INT_LIST: {
                final Integer value = WearDataLayerProtocol.parseDataMapInt(message, spec.valueKey);
                if (value != null) {
                    final String asString = String.valueOf(value);
                    if (!asString.equals(prefs.getString(spec.prefKey, null))) {
                        LOG.info("Galaxy Watch7: reflecting {} = {} from watch", subPath, value);
                        prefs.edit().putString(spec.prefKey, asString).apply();
                    }
                }
                break;
            }
            case INT:
            default: {
                final Integer value = WearDataLayerProtocol.parseDataMapInt(message, spec.valueKey);
                if (value != null && prefs.getInt(spec.prefKey, Integer.MIN_VALUE) != value) {
                    LOG.info("Galaxy Watch7: reflecting {} = {} from watch", subPath, value);
                    prefs.edit().putInt(spec.prefKey, value).apply();
                }
                break;
            }
        }
    }

    @Override
    public boolean useAutoConnect() {
        // Matches the stock app's behaviour: the connection is driven explicitly, not opportunistically.
        return false;
    }

    // The Wear connection is bidirectional (research notes §21): right after the phone opens the
    // Data Layer (phone->watch), the WATCH connects BACK to the phone on two RFCOMM services. If
    // nothing is listening for them, the watch tears the Data Layer down within ~90ms. We must run
    // RFCOMM servers (with SDP records) for both and keep the accepted sockets open.
    //  - WearProxy / CompanionProxy sysproxy tunnel (watch's TCP-over-BT / internet proxy).
    private static final UUID WEAR_PROXY_UUID = UUID.fromString("fafbdd20-83f0-4389-addf-917ac9dae5b2");
    //  - WearBluetoothHelper control channel.
    private static final UUID WEAR_HELPER_UUID = UUID.fromString("cee50b63-5f79-4727-8b5d-504eb07e1e22");

    private void startBackChannelListeners() {
        startBackChannelListener("WearProxy", WEAR_PROXY_UUID);
        startBackChannelListener("WearHelper", WEAR_HELPER_UUID);
    }

    private void startBackChannelListener(final String name, final UUID uuid) {
        final Thread thread = new Thread(() -> {
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            while (!Thread.currentThread().isInterrupted()) {
                try (BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord(name, uuid)) {
                    LOG.info("Galaxy Watch7 back-channel {}: listening on {}", name, uuid);
                    while (true) {
                        final BluetoothSocket socket = server.accept();
                        LOG.info("Galaxy Watch7 back-channel {}: accepted from {}", name, socket.getRemoteDevice().getAddress());
                        new Thread(() -> holdBackChannelSocket(name, socket), name + "-conn").start();
                    }
                } catch (final IOException e) {
                    LOG.warn("Galaxy Watch7 back-channel {}: listener error, retrying: {}", name, e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (final InterruptedException ie) {
                        return;
                    }
                }
            }
        }, name + "-listen");
        thread.setDaemon(true);
        thread.start();
    }

    // Hold the watch's back-channel socket open and drain whatever it sends. Keeping the socket
    // open (rather than closing it) is what stops the watch from tearing down the Data Layer. We
    // do not yet speak the sysproxy/helper protocols; draining and staying connected is the
    // minimum viable behaviour to test the bidirectional-connection hypothesis (§21).
    private static void holdBackChannelSocket(final String name, final BluetoothSocket socket) {
        final byte[] buf = new byte[4096];
        try (BluetoothSocket s = socket; InputStream in = s.getInputStream()) {
            while (true) {
                final int len = in.read(buf);
                if (len < 0) {
                    break;
                }
                LOG.info("Galaxy Watch7 back-channel {} <<< {} bytes: {}", name, len, GB.hexdump(buf, 0, len));
            }
        } catch (final IOException e) {
            LOG.info("Galaxy Watch7 back-channel {}: socket closed: {}", name, e.getMessage());
        }
    }

    private static String randomNodeId() {
        final byte[] raw = new byte[4];
        RANDOM.nextBytes(raw);
        return String.format(Locale.ROOT, "%02x%02x%02x%02x", raw[0], raw[1], raw[2], raw[3]);
    }
}
