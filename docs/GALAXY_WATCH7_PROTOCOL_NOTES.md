# Galaxy Watch7 <-> Phone Protocol — Reverse Engineering Notes

Session date: 2026-09-16
Test rig: Xiaomi Mi 10T (LineageOS 23.2 / Android 16, `lineage_apollon`, rooted via adb-root
userdebug build, no Magisk) + Galaxy Watch7. Captured via `bluetooth_hci_log` btsnoop +
`adb logcat` during a real pairing/setup run through the stock Galaxy Wearable
(`com.samsung.android.app.watchmanager`) + Watch7 plugin (`com.samsung.wearable.watch7plugin`).

## 1. Pairing / bonding (works with plain Android BT, no Samsung magic needed)

- Standard BR/EDR SSP pairing (numeric comparison) + LE bonding via normal Android
  `BluetoothDevice.createBond()`. No special phone-side requirement.
- App does check `com.samsung.android.security.keystore.AttestationUtils`
  (SAK/GAK hardware attestation, packages `com.samsung.android.app.twatchmanager.sak.*`
  in watchmanager.apk) but explicitly **disables** this for non-Samsung phones on
  Android 13+ (`VerificationManager.isSupportSAKVerify()` returns false, log:
  "Non Samsung Device - not support for Android T/U OS issue"). Falls back to plain
  `DevicePairingManager.requestPairing()` → `createBond()`. So a non-Samsung /
  custom-ROM phone does NOT need to pass Samsung's attestation at all for basic pairing.
- After BT bonding succeeds, watchmanager checks `PairingProblemChecker`
  (region mismatch via `BluetoothUuidUtil.checkDeviceRegion`, GMS version, tablet check,
  band reset flag) — all straightforward, no blocker for a normal phone.
- On success, watchmanager hands off to the device-specific plugin via intent
  `com.samsung.uhm.action.STEALTH_MODE` → `com.samsung.wearable.watch7plugin/
  com.samsung.android.waterplugin.activity.HMLaunchActivity`.
- The plugin then binds to `com.samsung.android.companionservice.service.WRCompanionService`
  and to Google's own `com.google.android.libraries.wear.companion.notification.service.
  CompanionNotificationListenerService`. Confirms Watch7 sits on top of the standard
  Wear OS ("Clockwork") companion stack, not a fully proprietary Samsung stack.
- Setup finishes with `DEVICE_CONNECTION_STATE_CHANGED isConnected=true` for a Wear
  "nodeId" (see below), then the stock Tips/OOBE screens.

Known-good Android system properties on the test phone (for reference, not
requirements we found to be enforced): `ro.boot.verifiedbootstate=orange` (unlocked
bootloader), `ro.build.type=userdebug`, `ro.security.keystore.keytype` empty (no
Samsung TEE keystore) — none of this blocked pairing once we simply retried a few times.

## 2. BT service / UUID map (from `dumpsys bluetooth_manager` + SDP capture)

Advertised SDP UUIDs on the watch (BR/EDR, once bonded):
```
0000110a / 0000110e / 00001112 / 0000111e / 0000111f   -- standard audio/HFP profiles
0aa62597-f040-42ef-82e4-0314fdfb7478   SAK_SECURE   (from BluetoothUuidUtil.java)
0bb62597-f040-42ef-82e4-0314fdfb7478   SAK_INSECURE (from BluetoothUuidUtil.java)
5e8945b0-9525-11e3-a5e2-0800200c9a66   DATA_LAYER   (from BluetoothUuidUtil.java)
fafbdd20-83f0-4389-addf-917ac9dae5b2   (unidentified, TLS-encrypted — see §4)
```
Channels opened during a session (RFCOMM):
- ch 0: RFCOMM multiplexer control
- ch 4: Hands-Free (audio proxy for calls, "Galaxy Watch7 (CLMP)" = presumably
  "Call/Media Proxy")
- ch 7: **DATA_LAYER** — main plaintext Wear-OS sync channel, see §3
- ch 10: TLS 1.3 tunnel over UUID `fafbdd20-...`, see §4

## 3. Channel 7 — DATA_LAYER (the interesting one for Gadgetbridge)

Framing: `[4-byte big-endian length][protobuf payload]`, one message per RFCOMM
UIH frame (tshark reassembles fragmented RFCOMM/L2CAP frames transparently).

This is **not** a Samsung-proprietary protocol — it is Google's internal Wear OS
"node sync" wire protocol (the private wire format behind the public
`com.google.android.gms.wearable.DataApi` / `MessageApi` / `CapabilityApi`), which
Samsung simply tunnels over their own RFCOMM channel instead of the usual
BLE/Wi-Fi transport used by AOSP Wear OS. This means the same protocol structure
almost certainly applies to *any* Wear OS 3+/4+/5 watch's companion sync, not just
Samsung's.

### Outer envelope (best-effort field mapping from ~13k captured messages)

```
Message (top-level):
  field 1  = message   -- one-of style payload, sub-field number tells the type
    field 7  = NodeInfo        (handshake / peer announcement)
    field 8  = SeqAck/Ping     (node ref + counter, sent early + periodically)
    field 9  = DataItem/PutDataRequest  (the actual "DataItem" pub/sub payload)
    field 10 = seen once, large (787 bytes) - not yet decoded (likely batched sync
               or capabilities dump)
  field 2  = requestId  (string, ~27 chars, base64url-ish random — used for ack/dedup)
  field 3  = varint, always seen = 1
  field 4  = varint, always seen = 1
  field 5  = varint, protocol/message version? (seen: absent, or 3)
```

NodeInfo (field 7):
```
field 1  = nodeId (8 hex chars, e.g. "e1b1cd8b" for the watch, "8de0a095" for phone)
field 2  = display name ("Mi 10T" / "Galaxy Watch7 (CLMP)")
field 3  = varint (large number on phone side, 0 on watch — maybe capability bitmask?)
field 4  = varint, always 3   (node type? "phone" vs "watch" not obviously encoded here)
field 5  = varint (1 phone-side, 2 watch-side — role indicator)
field 6  = varint, always 0
field 7  = varint, only on phone — a 19-digit number, looks like a GAIA/device ID
field 11 = varint = 36  (Android SDK level of that node)
```

SeqAck/Ping (field 8):
```
field 1 = varint, always 0xFFFFFFFFFFFFFFFF (uint64 -1 sentinel)
field 2 = message { field1=nodeId, field2=varint counter }
field 3 = varint, always 2
```

DataItem/PutDataRequest (field 9) — this is the interesting one, essentially a
reimplementation of `com.google.android.gms.wearable.PutDataMapRequest`:
```
field 1  = source package name (e.g. "com.google.android.gms",
           "com.samsung.wearable.watch7plugin")
field 2  = path, always of the form "wear://<destNodeId>/<...>" — this IS the
           DataItem URI used by the public DataApi (`Uri.parse("wear://.../path")`)
field 4  = payload — itself a nested key/value protobuf ("DataMap" wire format,
           see below), OR raw bytes/string for simple values
field 5  = varint — monotonically increasing per-path sequence/version number
field 6  = varint — seen 0 (probably "deleted" flag, 0=set/1=delete, unconfirmed)
field 7  = destination nodeId (8 hex chars)
field 9  = 40 hex chars = sha1-looking digest (of the payload, for change detection?)
field 10 = varint = epoch millis timestamp
```

DataMap payload wire format (nested inside field 4 above), repeated entries:
```
entry:
  field 1 = key name (string, e.g. "JSON_STRING", "from", "time")
  field 2 = message {
    field 1 = varint type tag  (2 = string-ish, 5 = seen for "time" -> probably long/int64)
    field 2 = the actual value, further nested/typed by the tag
  }
```
This matches the internal `DataMap.toByteArray()` layout used by real Wear OS apps
— i.e. Samsung/Google are just serializing a `DataMap` and shipping it as `field 4`
of the DataItem message above.

### Catalog of `wear://` paths seen during a plain pairing+setup (no health data
yet — watch had not synced any sensor data in this capture), ~150 distinct paths,
grouped by prefix (full list in `datalayer_full_decoded.txt` / `datalayer_decoded.txt`):

- `settings/com.google.android.clockwork.settings.SETTINGS/<node>` — stock Wear OS
  system settings sync
- `protogensettings/...` — Wear OS "protogen" settings tree: display
  (`always_on_display`, `screen_off_timeout_ms`), time (`is_24_hour_format`),
  accessibility (`talkback_present`, `wear_accessibility_gesture_enabled`, ...),
  notifications (`smart_replies_enabled`, `muted_apps`), locales
- `capabilities/<packageName>/<capabilityHash>/<capabilityName>` — CapabilityApi
  announcements, e.g. `capabilities/com.samsung.wearable.watch7plugin/9ca5170f.../`
  and `capabilities/com.google.android.gms/.../mdm%2Fringable`,
  `.../fast_pair_support`, `.../com.google.android.gms/<hash>/wear_installed_accounts`
- `w_module_sec_watch_setting*` / `w_module_sec_watch_setting_accessibility/*` —
  **Samsung-specific extension** on top of the same DataItem mechanism: dozens of
  individual accessibility toggles (magnify, color lens, cursor speed, grayscale,
  mono audio, touch-and-hold delay, etc.) — same wire format, Samsung-only paths.
  This is a good template for how Samsung layers custom features on the standard
  protocol — worth grepping watch7plugin's strings for the full `w_module_*` path
  list once needed.
- `wcs_extension/sysui/notification/switch/app_settings` — **notification routing
  settings** (which apps' notifications go to the watch) — directly relevant for
  Gadgetbridge's notification-forwarding feature.
- `audit_recording/data/event/common/CLOUD_SYNC` — telemetry/audit, ignorable
- `privacy_settings`, `accounts/data/wear_installed_accounts` — account/privacy sync
- `complication_providers/data/provider_app/<pkg>/` — watchface complication data
  source registration (e.g. `com.google.android.apps.maps`)
- `peers` — node list
- `ble_support`, `wearable/supported/features` — capability/feature negotiation at
  connection start

No health/fitness/step/heart-rate paths appeared in this capture — makes sense,
this was just the pairing+setup flow, watch had not produced any sensor data yet.
**Next capture should be a normal-use session** (wear the watch, let it sync steps/HR,
maybe trigger a phone notification) to see the actual health/notification DataItem
traffic Gadgetbridge would need to parse day-to-day.

## 4. Channel 10 — TLS 1.3 tunnel (UUID `fafbdd20-83f0-4389-addf-917ac9dae5b2`)

Real TLS 1.3 ClientHello/ServerHello observed (standard cipher suites
`1301/1302/1303/c02b/c02f/...`, `key_share` extension with x25519 group `001d`,
`psk_key_exchange_modes`). Preceded by a short custom framing header
(`03 55 04 1b 0a 04 1b 00 09 3c 756e6b6e6f776e3e` = contains ASCII `<unknown>`,
looks like a capability/negotiation frame before the TLS handshake starts — not
yet decoded).

Working hypothesis: this is Google's/Samsung's secure provisioning channel for
sensitive data (Google account tokens, Wi-Fi credentials, Samsung account —
matches that the user had to log into both Google and Samsung accounts during
setup). **Not needed for Gadgetbridge's core use case** (notifications, health
sync, settings) since Gadgetbridge doesn't need to provision watch OS accounts —
those normal DataItem paths all flow over the plaintext channel 7. Deprioritized;
would need either the TLS keys (not feasible without device compromise at the
TLS layer, e.g. Frida-hooking the app *before* it hands data to the TLS socket)
or just ignoring this channel entirely for a Gadgetbridge implementation.

## 5. Artifacts saved in this session's scratchpad

- `btsnoop/btsnoop_hci_success-pairing.log` — full btsnoop capture of a
  successful pairing+setup run (17.7 MB, 54201 packets)
- `btsnoop/btsnoop_hci_pre-restart.log` — earlier capture of 3 *failed* pairing
  attempts (kept for comparison; root cause of those failures was never fully
  confirmed — 4th attempt after a reboot succeeded cleanly)
- `logcat_attempt3.log` — full logcat of the successful pairing run (361k lines)
- `datalayer_decoded.txt` / `datalayer_full_decoded.txt` — parsed channel-7
  messages (path catalog + first 40 / all messages field-decoded)
- `parse_datalayer.py` — generic protobuf field dumper + `wear://` path extractor,
  reusable on any future capture (pipe tshark fields output into it, see docstring)
- `apks/watchmanager.apk`, `apks/watch7plugin.apk` — pulled APKs
- `apks/watchmanager_jadx/` — full jadx decompile of watchmanager.apk (pairing/
  attestation/setup-wizard logic, obfuscated but with several key unobfuscated
  packages — `sak`/`gak` attestation, `PairingProblemChecker`, `PairingFragment`)
- `apks/watch7plugin_jadx/` — full jadx decompile of watch7plugin.apk (37k+ java
  files, mostly **unobfuscated** — `com.samsung.android.companionservice.*` and
  `com.samsung.android.waterplugin.*` packages are fully readable class names,
  huge amount of untapped detail still in here for future sessions)
- `msgid_catalog.txt` — one example JSON payload per distinct `msgId` seen in this
  capture's MessageApi traffic

## 5b. Confirmed: field 10 = Google's real `MessageEvent` (MessageApi), field 9 = `DataItem` (DataApi)

Decompiling `watch7plugin.apk` (180MB, 8 dex files, jadx succeeded — mostly
**unobfuscated**, unlike watchmanager.apk) confirms this beyond doubt:
`com.samsung.android.companionservice.receiver.WearServiceMessageReceiver extends
com.google.android.gms.wearable.WearableListenerService`, overriding the real
public `onMessageReceived(MessageEvent messageEvent)`. So:
- field 9 messages (protobuf DataMap) = `DataApi` / `PutDataMapRequest` items
- field 10 messages (raw JSON string in `field 7`) = `MessageApi.sendMessage(path, data)`,
  `data` = UTF-8 JSON string, `path` = field 6 in our envelope (e.g.
  `/wcs_extension/sysui/notification/settings`)

This means the wire protocol we reverse-engineered on RFCOMM channel 7 IS the
literal, unmodified Google Wearable Data Layer transport protocol — Samsung has
not modified it, only added their own JSON message schema on top and tunnels the
whole thing over Bluetooth Classic instead of BLE/Wi-Fi Direct. Any Gadgetbridge
implementation of this transport should work for other/generic Wear OS
"Bluetooth Classic companion" watches too, not just Samsung ones.

### Full protocol surface map (from `com.samsung.android.companionservice.*`, all class
names below are genuine, not guessed — this is the feature-to-code map for future
implementation work):

```
aboutgalaxywearable/messenger/WatchDumpMessageReceiver       -- device info dump
backuprestore/messagecommunication/{backup,restore,delete,autobackup}/*Receiver
capability/CapabilityExchangeMessageReceiver                 -- capabilities
cmc/companion/CmcWearableListenerService
fmp/managers/receiver/FMPMessageReceiver                     -- Find My Phone
fmw/managers/FMWMessageReceiver                               -- Find My Watch
notifications/datalayer/SettingsMessageReceiver               -- notification settings
nowbar/message/NowbarMessageReceiver                          -- "Now Bar" widget
oobe/datalayer/SetupWizardExchangeDataReceiver                -- setup wizard
pm/WPMWearableListenerService                                 -- package manager (app install/sync)
quicklaunch/QuickLaunchDataReceiver
quickpanel/managers/QuickPanelDataReceiver
receiver/WearServiceMessageReceiver                           -- feature-spec exchange (/wearservices/feature/spec)
screencapture/ScreenCaptureMessageReceiver
selfdiagnostics/wearable/SDMessageReceiverService
settings/SettingsWearableListenerService                      -- general settings sync (DataApi)
sos/sosmessagereceiver/SOSMessageReceiver
stwatchplugin/STWatchPluginListenerService
tiles/message/receiver/TilesMessageReceiver
watchapps/tx/WatchAppsMessageReceiver
watchface/filetransfer/wifi/receiver/WifiDirect{Backup,OOBE,}DataReceiver -- large
    file transfer (watchfaces etc.) switches to Wi-Fi Direct, not RFCOMM!
watchface/transaction/WatchFaceMessageReceiver
watchstatus/devicecare/DcWearableListenerService
watchstatus/messenger/{AboutWatchMessageReceiver,ReportDiagnosticMessageReceiver,
    WatchStatusMessageReceiver}
```

Note: watchface/wallpaper file transfer explicitly switches transport to Wi-Fi
Direct (separate from the RFCOMM channels documented here) — expect a third
transport layer for anything involving large binary blobs (watchface files,
photos). Not investigated this session.

### JSON `msgId` catalog observed in this capture (MessageApi payloads, one example
each — full catalog with complete JSON in `msgid_catalog.txt`):

Media control (directly useful for Gadgetbridge's media-control feature):
- `volume` — `{"deviceId","data":{"playbackType","volume","headsetConnected","btOutput","maxVolume","warningVolume","dndModeOn","muteAllSoundsOn"}}`
- `playbackstate` — `{"data":{"appId","appTitle","queueItemId","state","position","actions","speed","customActions"}}`
- `mediametadata` — `{"data":{"appId","appTitle","mediaId","title","artist","album","duration","albumArt"}}`
- `audio_output` — connected BT audio device list

Notifications (Gadgetbridge's core feature):
- `notification_allowed_packages` — full package allow-list, `sender:"mobile"`
- `notification_package_allow_status` — single package toggle
- `notification_feature_exchange` / `notification_settings_feature_exchange` — capability negotiation (voip, mutePending, multiUser, enhancedPhoneAlertMute, etc.)
- `support_suggested_replies`, `text_template_sync` — quick-reply templates + emoji

Device/feature negotiation:
- `feature_exchange` — phone hardware info (screen size, locale, timezone, vendor, model) sent once at setup
- `phone_setting_top_priority_features`, `active_info`, `oneui_version`, `companion_state`, `set_device_activation`, `request_data` (batched pull of multiple msgIds at once), `oobe_complete`

Apps / watchfaces:
- `apps_order`, `apps_icon`, `apps_restore`, `apps_default_folder_color_set`,
  `update_package_list_to_phone` (installed-app list on both sides),
  `request_package_list_update`, `watchface_list`, `watchface_order`,
  `change_active_watchface`

Contacts sync (`contacts-*-ind` messages, own mini-protocol):
- `contacts-country-iso-changed-ind`, `contacts-sortorderchanged-ind`,
  `contacts-showfrequentlycontactedchanged-ind`, `contacts-myprofiledeleted-ind`,
  `contacts-lock-screen-emergency-contacts-access-changed-ind`,
  `contacts-getfeature-push`, `contacts-sync-permission-status-for-china-model`

Misc: `menu` (BT reconnect state), `Push_Update_Key` (opaque base64 blob, likely
crypto key material — related to channel-10 TLS layer?), `syncData`,
`bnr_restore_get_devices_list_info`, `wappslist`/`wappsStorage`,
`quick_settings`/`quick_settings_icon`.

## 5c. Live-traffic confirmation (adb-automated test, no health data — matches project
scope: Gadgetbridge replaces the Wear Manager app for pairing/settings/notifications;
health/vitals is intentionally out of scope, planned separately via "Open Vitals")

Triggered via `adb shell input tap` (UI automation on the real Wearable app settings
screen) and `adb shell input keyevent KEYCODE_VOLUME_UP/DOWN` — no manual interaction
needed, fully scriptable for regression testing later:

- **Settings toggle** ("Telefonbenachr. auf Uhr anzeigen" switch in watchmanager's
  Notifications screen) → confirmed wire message:
  ```json
  {"msgId":"set_settings_alerts_on_phone","sender":"mobile","msgType":4,"data":{"value":1}}
  {"msgId":"set_settings_alerts_on_phone","sender":"mobile","msgType":4,"data":{"value":0}}
  ```
  Sent twice each time (once per... redundant send, or dup on two connections/nodes) on
  path `/wcs_extension/sysui/notification/switch/common_settings`, matched by a
  DataItem update on the same path carrying the same info as a protobuf DataMap
  (`JSON_STRING` key) — so this particular setting is synced via *both* mechanisms
  simultaneously.

- **Volume** — pressing hardware volume keys on the phone (no media app needed, just
  system volume) triggers an automatic push:
  ```json
  {"msgId":"volume","msgType":"4","result":"0","reason":"0","deviceId":"e1b1cd8b",
   "data":{"appId":"","appTitle":"","appVersion":"","playbackType":"1","volume":"6",
   "headsetConnected":"0","btOutput":"0","maxVolume":"15","warningVolume":"10",
   "dndModeOn":"0","muteAllSoundsOn":"0"}}
  ```
  followed by watch ack `{"msgId":"volume","msgType":"0","deviceId":"8de0a095","data":{"appId":""}}`.
  Confirms `volume` is a live push-on-change message, not just a setup-time snapshot.

- **Test notification via `adb shell cmd notification post`** did NOT reach the watch —
  confirmed in logcat the notification was posted locally (`NotifShellCmd`), but never
  appeared as a MessageApi/DataItem event on the BT wire. Root cause: it's posted under
  the `com.android.shell` package identity, which is not in the
  `notification_allowed_packages` allow-list synced earlier — Samsung's notification
  listener on the phone filters by real source package before relaying. **A real
  notification from an actually-installed, allowed app is needed to capture the
  live notification-push wire format** — this is the one thing that still needs a
  physical action (or installing/using a real messaging app) rather than pure adb.

- Also observed spontaneous background traffic unrelated to our actions: ongoing
  contacts/call-log backup sync (`receiving-file-completed-ind` with
  `SAContactJSONFile*.zip` / `SALogsJSONFile*.zip` values, `contacts-*-ind` messages)
  — the watch continuously syncs contacts/logs in the background after setup.

## 5d. Watch-side ADB access (huge unlock for future sessions)

The watch itself supports ADB over WiFi (Developer options → ADB debugging + Debug
over Wi-Fi → pair with code, then `adb pair <ip>:<pairport> <code>` once, then
`adb connect <ip>:<connectport>`). Confirmed working — watch identifies as
`model:SM_L310 device:fresh7bl product:fresh7blue`, Android 16 / SDK 36, **no root**
on this production/retail build (`adbd cannot run as root in production builds`),
but full shell access otherwise.

Watch-side companion/system packages confirming the architecture from §5b:
```
com.google.android.wearable.app              -- core GMS Wearable app (DataApi/MessageApi host)
com.google.android.wearable.healthservices    -- health (out of scope for this project)
com.samsung.android.wcs.extension             -- "Wear Companion Service" extension (matches
                                                  capabilities/com.samsung.android.wcs.extension/... paths)
com.samsung.android.wearable.sysui             -- matches wcs_extension/sysui/... paths
com.samsung.android.wearable.media.sessions    -- matches volume/playbackstate/mediametadata
com.samsung.wear.contacts.sync                 -- matches contacts-*-ind messages
com.samsung.android.wear.shealth               -- Samsung Health (out of scope)
com.samsung.android.wearable.samsungaccount, .setupwizard, .setupwizard.fota, etc.
```
Pulled `com.samsung.android.wcs.extension` APK (`/system/priv-app/ClockworkWcsExt/
ClockworkWcsExt.apk`) to `apks_watch/wcs_extension.apk` for future decompilation —
this is likely the single most useful watch-side APK to reverse (it's the extension
that actually implements most of the `wcs_extension/*` message paths).

**Live notification-push wire format still NOT captured**: tried repeatedly via
`adb shell cmd notification post` (posts locally under `com.android.shell` identity)
and via `am start -a android.intent.action.SET_TIMER` (to get a real notification
from the allow-listed `com.android.deskclock`) — neither produced any channel-7
traffic at all (confirmed both via phone+watch logcat, zero relevant log lines, AND
via a fresh btsnoop pull showing zero packets in the test window). The `SET_TIMER`
intent doesn't seem to be implemented by this LineageOS build's clock app; blind
UI-automation taps to manually start a timer were unreliable (wrong screen/coords).
**This needs either a real notification from an actually-used messaging app, or a
manually-started timer/alarm on the watch's clock app** — left for a future session
with user interaction.

## 5e. Extended max-capture round (UI-automated on BOTH phone and watch via adb,
no manual interaction) — `datalayer_maxcapture_decoded.txt` / refreshed
`msgid_catalog.txt` (now ~63 distinct msgIds, 578 distinct `wear://` paths, full
walk through every settings screen on the phone side + dashboard shortcuts + Find
My Watch + quick panel / app screen swipes directly on the watch's own touchscreen
via `adb -s <watch-ip:port> shell input tap/swipe`)

New msgId types discovered in this round, several are high-value for Gadgetbridge:

- **`notification_history_from_watch`** (19 occurrences) — the watch reports back
  which notifications it received/dismissed:
  ```json
  {"msgId":"notification_history_from_watch","packageName":"com.samsung.android.timezone.updater","receivedTime":1789566909945}
  ```
  This is the ack/history mechanism — relevant if Gadgetbridge wants to track
  read/dismissed state.
- **`toast`** — simple transient UI message push, e.g. connection-status toasts:
  ```json
  {"msgId":"toast","msgType":"4","deviceId":"BCA080DB0807","data":"{\"text\":\"Verbindung zum Server wird hergestellt\"}"}
  ```
  (note: `data` here is itself a JSON string, i.e. double-encoded — this pattern
  recurs for several msgIds, worth watching for when implementing a generic parser)
- `apps_uninstall` — `{"data":{"packageName":"com.android.vending","isUpdated":true}}`
- `request_companion_state` / `companion_state` — connectivity/setup state machine
  (seen values include `need_to_turn_on_wifi_on_phone`)
- `crypt_Initial_Sync_Logs`, `crypt_Request_Initial_Sync_Contact`,
  `crypt_Request_Sync_Emergency_Contact` — contacts/call-log sync requests, "crypt_"
  prefix suggests the actual payload for these gets encrypted separately (likely
  routed through the TLS channel 10 tunnel from §4, or symmetric-encrypted inline —
  not yet determined) despite the outer envelope/msgId itself being visible in
  plaintext on channel 7
- `Response_Initial_Sync_Contact`, `Response_Initial_Sync_Logs`,
  `logs-activatesync-{req,rsp}`, `calllog-markasread-req`,
  `contacts-getfeature-{req,rsp}` — more of the contacts/call-log sync mini-protocol
- `apps_screen_featured_apps` — Play Store "featured for watch" app suggestions

Confirmed working watch-side gestures reachable via `adb shell input`:
- `input swipe 240 50 240 400 300` from top edge → Quick Panel (battery %, AOD,
  Bluetooth/WiFi status, sound mode, settings shortcut)
- `input swipe 240 450 240 50 300` from lower area → App Screen / app grid
- watch confirmed connected to **WiFi directly** (not just BT to phone) — relevant
  since some features (Wi-Fi Direct file transfer, the TLS channel) depend on this

Phone-side "Find My Watch" (`Uhr finden` on the watchmanager dashboard → `Starten`)
was triggered and stopped via UI automation — generates `fmw`-prefixed traffic
(class `FMWMessageReceiver` on the watch, not yet decoded in detail, but confirmed
reachable/triggerable this way for a future focused capture).

## 5f. Notification bridging mechanism — CONFIRMED path scheme + exact filter rule
(via `com.google.android.gms` process logcat, uid 4183, while triggering a real
10-second timer notification through full UI automation of the stock Clock app —
digit-by-digit verified via uiautomator, not blind taps)

**Correction (verified via `ps` and `settings get secure enabled_notification_listeners`):**
the bridge does NOT run in `com.google.android.gms` — it runs inside
`com.samsung.wearable.watch7plugin:persistent` (confirmed: pid seen in logcat
belongs to this process), which is the system's registered
`NotificationListenerService`:
```
com.samsung.wearable.watch7plugin/com.google.android.libraries.wear.companion.notification.service.CompanionNotificationListenerService
```
i.e. Samsung bundles/uses Google's `companion` library class name but hosts and
runs it themselves inside watch7plugin's own persistent process — there is no
separate always-on GMS wearable listener process on this device; watch7plugin
*is* the bridge. Classes visible in logcat tags `BridgeModeModel`,
`NotificationPipeline`, `NotifDismissalHandler`, `DataClientWriter` all live in
that process, with a Samsung-specific filter layered on top
(`Notification4Watch7_NB` tag = "Notification for Watch7 - Notification Bridge").

**Confirmed NOT the cause of the shell-notification dead end**: notification
listener access is correctly granted (`enabled_notification_listeners` shows the
watch7plugin listener as active), and toggling the Watch7 app's own
"phone apps allowed" notification settings makes no difference either — retried
4 times total (§5f below) via different angles (plain post, post after granting
app-level allow toggle, checked listener registration) with consistently zero
bridge-pipeline log output. The block is upstream of both of these: most likely
`NotificationManagerService` simply never dispatches `cmd notification post`'s
synthetic shell-UID notifications to any `NotificationListenerService` at all
(an Android platform behavior, not something Samsung's app controls) — not
confirmed with certainty, but no further avenue to test this via adb was found
this session.
Flow for every posted notification on the phone:

1. GMS `NotificationListener` receives it (standard `NotificationListenerService`)
2. `NotificationPipeline`: "Processing new notification: <key>"
3. `BridgeModeModel`: `is bridgable: true/false` (basic eligibility check)
4. Samsung's `Notification4Watch7_NB` → `NotificationChecker.checkIgnoreCase`:
   application-specific ignore rules. Confirmed rule seen: **notifications with the
   `ONGOING_EVENT` flag are unconditionally ignored** (log: `checkIgnoreCase -
   onGoing`, then `NotificationBridgeCallback.onNotificationPosted-Ignored package
   : [0] <pkg>`). This is why our timer/alarm test (which posts an
   `ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE|HIGH_PRIORITY|LOCAL_ONLY` notification
   by design) never reached the watch, and is a strong hint that Android's
   `Notification.FLAG_LOCAL_ONLY` may also independently be respected — both flags
   were present here so which one(s) actually gate it isn't 100% isolated yet, but
   `checkIgnoreCase - onGoing` is the one that actually logged/fired.
5. If NOT ignored, the notification gets written as a **DataItem** (not a
   MessageApi message!) at path:
   ```
   /bridger/stream_item:<watchNodeId>:<packageName>:<notificationId>:<tag>
   ```
   e.g. `/bridger/stream_item:e1b1cd8b:com.android.deskclock:2147483644:null_tag:...`
   — this is Google's own, standard (and historically publicly documented, though
   since deprecated/undocumented on developer.android.com) Wear OS "Notification
   Stream" DataItem convention, used generically by the AOSP/GMS notification
   bridge, NOT something Samsung invented. Confirms notification *content* almost
   certainly travels as a DataItem (protobuf DataMap, same mechanism as §3), not as
   JSON MessageApi — unlike the settings/media/contacts messages catalogued in §5b,
   which use plain JSON over MessageApi.
6. On dismissal: `NotifDismissalHandler` logs "dismissed on phone, deleting data
   item <path>" and `DataClientWriter` issues a `DeleteRequest` for that exact path
   — i.e. dismissing on phone deletes the DataItem, which is presumably how the
   watch also mirrors dismissal (Gadgetbridge would need to do the same: delete/
   update the DataItem on dismiss/read to keep the watch's notification list in
   sync, not just "not send" it again).

**Still NOT captured**: the actual DataItem *payload* (title/text/icon/actions
encoding) for a real, successfully-bridged (non-ongoing) notification — every
avenue tried this session to trigger a real, non-ongoing, allow-listed
notification purely via adb failed:
- `cmd notification post` → confirmed via logcat to never even reach
  `NotificationListener`/`BridgeModeModel` at all (posted locally only, tag
  `NotifShellCmd`) — likely filtered by `NotificationManagerService` before
  listener dispatch because it's posted under the shell UID/synthetic channel,
  independent of the ONGOING rule above.
- `am start -a android.intent.action.SET_TIMER` → not implemented by this
  LineageOS build's Clock app (no-op).
- Full UI automation of a real timer (digit-by-digit verified, confirmed firing,
  confirmed reaching the GMS bridge pipeline) → filtered by the `ONGOING_EVENT`
  rule, as intended by design (watches have their own timers).

**For a future session**: trigger a genuinely non-ongoing notification from an
actually-allow-listed app — either a real SMS/message, or find some other
non-ongoing system notification (e.g. a completed download from
`com.android.vending`, or wait for a periodic one like
`com.samsung.android.timezone.updater`, which we already have indirect evidence
DID get bridged once via a `notification_history_from_watch` catalog entry earlier
in this session — see §5e) while running the same phone+watch logcat plus
btsnoop capture setup used here, to capture the actual DataItem payload bytes for
`/bridger/stream_item:...`.

## 5g. NOTIFICATION PAYLOAD — FULLY CAPTURED (real WhatsApp-style test notification,
via a notification-testing app installed by the user, `com.xinesoft.notifications`,
title "Test" / text "Das ist ein test" / app label "WhatsApp"/color WhatsApp-green
0xff075e54). This closes the last major protocol gap of the session.

Full end-to-end flow confirmed via `com.samsung.wearable.watch7plugin:persistent`
process logcat + matching raw bytes on RFCOMM channel 7:

1. `NotificationListenerService.onNotificationPosted` fires
2. `BridgeModeModel`: `is bridgable: true`
3. `Notification4Watch7_NB`: per-app enabled check (`Watch.isEnabled-... allow
   state : true`), alert-type-from-channel-importance check, DND/mute check,
   `PendingManager.getPendingState` → `SEND` or `QUEUE` (queued if e.g. watch not
   being worn / DND active — ties into the "only forward when phone screen off"
   bridging mode the user mentioned; not fully characterized but the queue/send
   split is the mechanism)
4. `NotificationSender`: "Putting notification data item
   /bridger/stream_item:<watchNodeId>:<pkg>:<notifId>:<tag>"
5. `DataClientWriter`: writes it as a DataMap **with 3 Assets attached**
   (`numAssets=3`, `dataSz=1519`) — icons/images are NOT inline in the DataMap,
   they're separate binary Assets referenced by digest (see raw capture below —
   several ~990-byte frames between the settings sync and the final DataMap
   frame are these binary asset chunks, not parseable as protobuf, i.e. probably
   raw bitmap/PNG bytes for the small icon / large icon / app icon).

### Confirmed DataMap field schema for a `/bridger/stream_item:...` PUT (this is
now a concrete, implementable spec for Gadgetbridge to push notifications that
Watch7's UI will render correctly):

```
app_name              (string)   "Benachrichtigungen" (shows the source app's
                                  *label*, not its package — was literally this
                                  test app's Android app label)
color                 (int64)    ARGB color as huge uint64 (0xff075e54-style,
                                  matches the notification's accent color)
content_intent_id     (string)   UUID referencing a PendingIntent (for tap-to-open
                                  — presumably the watch can trigger this back via
                                  a companion action id, not the actual intent)
defaults              (int32)    2
group                 (string)   notification group key as set by the posting app
group_alert_behavior  (int32)    0
group_key             (string)   "<userId>|<packageName>|g:<group>"
has_content_intent     (bool)    true
icon                   (int32)   1  (asset reference index, actual bitmap is a
                                     separate Asset blob)
is_emergency            (bool)   false
is_group_summary        (bool)   true (this particular notif was the group summary)
is_small_icon_vector_drawable (bool) true
is_work_profile          (bool)  false
notification_flags      (int32) 536 (raw Android Notification.flags bitmask)
notification_style      (int32) 1
only_alert_once         (bool)  true
post_time_millis        (int64) epoch millis
revision                (bytes) 2-byte binary value (purpose unclear, maybe an
                                internal Bundle-diff cookie)
show_when               (bool)  true
sub_text_html/sub_text_raw (string) "WhatsApp" — this is the notification's
                                *subtext* which the poster app set to its own
                                display name (common Android convention for
                                showing the source app name in the notif UI)
supplemental_assets      (bytes, empty here) — presumably a list-type field when
                                populated (e.g. picture/bigPicture style)
supplemental_data        (int32) 2
text_html/text_raw       (string) the notification body text ("Das ist ein test")
title_html/title_raw     (string) the notification title ("Test")
user_id                  (int32) 0
wearable_actions          (bytes, empty here) — action buttons when populated
                                (this test notif had `actions=2` per the
                                StatusBarNotification dump, but wearable_actions
                                came through empty — actions may be delivered via
                                a separate Asset too, not fully confirmed)
when                     (int64) epoch millis (matches Notification.when)
```
Every field follows the same generic DataMap encoding as §3 (`{key:string,
value:{typeTag varint, value}}`); `_html` vs `_raw` field pairs both present with
identical content in this test (html-escaping presumably differs when the source
text has markup).

### Confirmed DELETE (dismissal) on `/bridger/stream_item:...`

Dismissing (either side) triggers a DataItem **delete** — same path, envelope
`field 9` (DataItem), but with **`field 6 = 1`** where a PUT has `field 6 = 0`.
This CONFIRMS field 6 in the DataItem envelope (see §3) is indeed a delete flag,
not just theorized. `NotifDismissalHandler` + `DataClientWriter.DeleteRequest`
on phone-initiated dismiss; a `NotifDataItemDelHndlr` on the phone side also
reacts to delete events (including its own, self-loop observed — a genuine
watch-initiated dismiss would look the same on the wire, just WATCH→PHONE
direction instead).

### Bonus catalog additions seen in the surrounding traffic (unrelated to
notifications specifically, picked up incidentally in this capture window):
- `set_settings_alerts_on_watch` (mirror of the phone-side
  `set_settings_alerts_on_watch`/`_on_phone` pair — watch has its own local
  settings copy kept in sync both ways)
- Full JSON dump of the watch's local notification settings object via the
  `common_settings` DataItem:
  ```json
  {"TAG":"Notification4_ NotificationSettings","advancedSettings":{"indicator":true,
   "intReadNotificationAloud":0,"readNotificationAloud":false,"showWithDetails":true,
   "turnOnNewApps":false,"turnOnScreen":false,"watchBixbyEnableState":false},
   "muteNotificationsOnPhone":false,
   "showPhoneNotificationsOnWatch":{"whenPhoneNotInUse":false},
   "watchOneUIVersion":8.0}
  ```
  **`showPhoneNotificationsOnWatch.whenPhoneNotInUse`** is exactly the "avoid
  duplicate notifications" mode the user mentioned — a single boolean toggle
  inside this settings blob, not a separate message type.
- `wcs_extension/internal/status` — `{"wearingState": <bool>}`, pushed
  watch→phone whenever on-wrist detection changes (ties into the PendingManager
  QUEUE vs SEND decision in step 3 above)
- `w_module_keyboard_textshortcuts`, `w_module_keyboard_user_contents/
  request_selected_languages` — quick-reply keyboard shortcuts sync (relevant
  for Gadgetbridge's reply-from-notification feature later)
- `bixby/bixbyvoice/quickCommandSupportedInMobile` GET/POST — Bixby capability
  negotiation, safely ignorable for Gadgetbridge

## 5h. THE GENERIC SETTINGS WRITE MECHANISM — fully solved (this is the single
most implementation-ready finding of the whole session)

Toggled 4 representative settings live (hourly chime under Töne und Vibration,
Always On Display under Anzeige, "Automatisch einschalten" under Akku, "Klopf
Klopf" gesture under Tasten und Gesten) while running phone logcat, and simply
**visiting** each settings screen (without touching anything) already logs its
**entire path catalog** via `registerWatchSettingsContentObserver()` calls — no
need to individually toggle every single setting to learn its path.

### The write path (from `com.samsung.wearable.watch7plugin:persistent` logcat,
tags `WatchSettings_Watch7`/`WatchSettings_Watch7*`, classes `SettingsCompanion`,
`SettingsDataManager`):

```
SettingsDataManager.putDataItem(deviceId, uriPath, key, value, valueType)
  → SettingsCompanion.put(deviceId, "GW", uriPath, value)
  → SettingsCompanion.putWatchDataItem(deviceId, SettingsDataItem(uri, key, value, valueType, fromWhere, dataOwner))
  → writes a DataItem at  wear://<watchNodeId><uriPath>
```

For a **boolean** setting the DataMap is trivially simple — a single key/value pair:
```
key = "booleanIsChecked"
value = true | false
valueType = "Boolean"
```
i.e. the entire DataMap payload for e.g. toggling Always-On-Display or a gesture
is just `{"booleanIsChecked": <bool>}` written to
`wear://<watchNodeId>/w_module_sec_watch_setting/display/always_on_display`
(or `.../display/always_on_display/disabled` — both variants seen registered,
not fully disambiguated which is authoritative). **This generalizes: to change
ANY simple on/off watch setting, Gadgetbridge just needs to PUT a DataItem with
this exact one-key DataMap shape at the right path from the catalog below** — no
per-feature custom message format needed, unlike the JSON MessageApi paths in
§5b which vary per msgId. Non-boolean settings (lists/selections, numeric sliders)
presumably follow the same `SettingsDataItem` pattern with a different
`key`/`valueType` (e.g. `selectedIndexIsChecked`-style key + `valueType=Int`, or
similar) — not empirically confirmed yet, but same mechanism, just try key names
matching the path's last segment convention (`selected_item`, `list`, etc.).

Full round-trip confirmed via `DataItemMonitor.onDataChanged()` /
`SettingsWearableListenerService.processDataItem()` logs — the watch's own
process mirrors every phone-driven change back through the identical pipeline
(`fromWhere=GW` = "Galaxy Wearable" for phone-initiated; presumably `fromWhere=
WATCH` for on-watch-initiated changes, both directions go through the same
`SettingsCompanion`).

### Full catalog of confirmed settings paths (103 total, `w_module_sec_watch_setting*`
prefix implied unless shown), captured just by opening Sound Settings (+ Call
Vibration + System Sounds sub-screens), Display Settings, and Buttons & Gestures
Settings — saved in full at `all_settings_paths.txt`:

**Accessibility** (separate top-level prefix `w_module_sec_watch_setting_accessibility/`):
`mute_all_sound`, `talkback` (+ the larger list already catalogued in §5c/§5e)

**Display** (`display/`): `always_on_display`, `always_on_display/disabled`,
`auto_launch_media_control`, `auto_low_brightness`, `bold_font`, `brightness`,
`brightness_max_level`, `font_size`, `font_size_max`, `font_style/list`,
`font_style/selected_item`, `outdoor_mode`, `screen_time_out/list`,
`screen_time_out/selected_item`, `show_charging_info`, `show_last_app/list`,
`show_last_app/selected_item`, `show_last_app/selected_value`,
`touch_sensitivity`, `wake_on_touch_screen`, `wake_on_turning_bezel`,
`wake_on_wrist_raised`

**Sound settings** (`sound_settings/`): `long_vibration`,
`notification_sound/{list,selected_item,selected_name}`,
`notification_vibration/{list,selected_item,selected_name}`,
`ringtone/{highlights_only,name/list,selected_item,selected_name,usertone/list}`,
`ringtone_vibration/{list,selected_item,selected_name}`, `sound_mode`,
`system_sounds/{charging_sound,dialing_keypad_tone,hourly_chime,samsung_keyboard,
ticking_sound,touch_sound,unlock_sound}`,
`system_vibration/{charging,dialing_keypad,samsung_keyboard}`,
`vibration_feedback`, `vibration_intensity(/list)`, `vibration_type(/list)`,
`vibration_with_sound`,
`volume/{accessibility,ai_assistant,bixby,media,notifications,ringtone,system}`

**Advanced / buttons & gestures** (`advanced/`):
`action_button/{how_to_start_action,list,selected_health_id,selected_item,siren/enabled}`,
`bixby/enabled`, `digital_assistant/{list,selected_name}`,
`disconnection_alert/{dont_get_alarm,sa_place_address,selected_item}`,
`double_press/{feature_list,list,selected_package}`,
`gestures/{answer_calls,dismiss_alerts_and_reject_calls,quick_action,
quick_action/alarm_alerts/setting,quick_action/calendar_alerts/setting,
quick_action/media_controller/setting,quick_action/reminder_alerts/setting,
quick_action/watchface/second_action_detail_setting,
quick_action/watchface/second_action_enabled}`,
`labs/quicklaunch/{enabled,launch_package,selected_health_id}`,
`media_control/{auto_launch_media_controls,show_ongoing_icon}`,
`press_and_hold/{list,selected_item,selected_name}`,
`recents_key/{selected_item,selected_name}`,
`status_indicator/{list,selected_item}`, `theater_mode/enabled`

**Misc top-level**: `bedtime/sync_bedtime_mode`, `phone/dnd/dnd_turn_on_now`,
`phone/dnd/sync_do_not_disturb/enabled`, `psm` (power saving mode)

This is almost certainly not the FULL settings tree (Security/Accounts/Google
screens weren't visited with this specific logging active this round, and Health/
Pay are out of scope anyway) but it's a very large, directly implementable core.

## 5i. Google/Samsung account requirement — EMPIRICALLY VERIFIED: NOT required

Explicitly tested: removed the phone's Google account entirely (`dumpsys account`
confirmed zero accounts), factory-reset the watch, and re-paired from scratch.
No Samsung account was ever present on this phone throughout the whole session
(no Samsung account app even installed).

Result: **everything still worked identically** — BT bonding (`BOND_TYPE_PERSISTENT`,
authenticated+encrypted), HFP connect, app handoff to watch7plugin, and the full
RFCOMM channel-7 DataLayer traffic (15267 packets captured for this account-less
pairing session alone) — including the dashboard showing live battery % synced
from the watch, which requires a working DataItem channel.

**Conclusion for Gadgetbridge**: Google Play Services must be *installed and
enabled* as a package (checked by `GoogleRequirementUtils.isGooglePlayServiceAvailable()`
during setup), because the whole transport is built on
`com.google.android.gms.wearable.*` classes — but an actual signed-in Google or
Samsung account is **not required** for pairing, settings sync, or (very likely,
consistent with this finding, though not independently re-tested account-less)
notification bridging. Account-gated features are limited to things out of this
project's scope anyway: Play Store app downloads/updates for the watch plugin,
Samsung Cloud backup/restore, Samsung Pay, Bixby account features, and
`cloudsync_key_timestamp`-style cross-device cloud sync.

## 7. Phase 1 implementation status (Gadgetbridge code, branch `feature/galaxywatch7-support`)

Built and compiled successfully against the real Gadgetbridge framework:
- `devices/galaxywatch7/GalaxyWatch7Coordinator.java` — device detection (name pattern
  `Galaxy Watch7.*`), `BONDING_STYLE_BOND`, `DeviceKind.WATCH`, manufacturer Samsung
- `service/devices/galaxywatch7/GalaxyWatch7DeviceSupport.java` — `AbstractBTBRDeviceSupport`
  subclass, connects to `WearDataLayerProtocol.SERVICE_UUID_DATA_LAYER` via SDP, sends the
  NodeInfo handshake on connect, logs incoming bytes
- `service/devices/galaxywatch7/WearDataLayerProtocol.java` — minimal protobuf writer +
  the message builders from §5 (NodeInfo handshake, generic boolean-setting DataItem PUT,
  4-byte length-prefix framing)
- `DeviceType.GALAXY_WATCH7` registered, new string resource added

**Confirmed working end-to-end** (via real device testing against the actual Watch7,
disable-user'ing Samsung's `watchmanager`/`watch7plugin` first — they're a registered
`NotificationListenerService` and get auto-relaunched by the system after a plain
`force-stop`, so `pm disable-user` + stripping `enabled_notification_listeners` was
needed to keep them down for a clean test):
- Gadgetbridge's `DiscoveryActivityV2` correctly matches the already-bonded watch to our
  coordinator by name
- Standard Android bonding flow works (choose "Nein"/No on the "Begleitgerät"/companion
  dialog — "Ja" routes through `CompanionDeviceManager` treating the device as LE because
  `BluetoothDevice.getType()` reports `DEVICE_TYPE_DUAL` for this watch, which hangs
  waiting for a BLE scan match that never comes; this is existing Gadgetbridge
  `BondingUtil` behavior, not specific to this device, but worth knowing when testing)
- SDP correctly resolves `SERVICE_UUID_DATA_LAYER` to a live RFCOMM channel
- **A full successful connection was directly observed on the wire** during debugging
  (raw HCI capture, not through our own app's socket — see below): `SABM Channel=N
  (UUID: 5e8945b0-...)` → `UA Channel=N` (watch accepts) → real bidirectional `UIH`
  application data. This confirms the transport/UUID/framing understanding is correct.

**Known blocking issue, unresolved as of this session**: `BluetoothDevice
.createRfcommSocketToServiceRecord(...).connect()` (used by the shared
`BtBRQueue`/`AbstractBTBRDeviceSupport` framework code, not something specific to our
new files) consistently throws:
```
java.io.IOException: read failed, socket might closed or timeout, read ret: -1
    at android.bluetooth.BluetoothSocket.readAll(BluetoothSocket.java:1364)
    at android.bluetooth.BluetoothSocket.waitSocketSignal(BluetoothSocket.java:1293)
    at android.bluetooth.BluetoothSocket.connect(BluetoothSocket.java:662)
```
This happens consistently, reproduced many times, under multiple conditions:
- With Samsung's own apps fully killed/disabled (ruled out: competing app holding the
  channel)
- After toggling Bluetooth off/on for a clean stack state (ruled out: stale multiplexer
  state from a previous attempt)
- With an explicit `getConnectDelayMillis()` override (tried 500ms; ruled out, at least
  at that value: simple "connected too fast" race — the connect attempt failed in under
  100ms even with the delay applied beforehand)

Raw HCI evidence shows the failure happens root before the app ever sends `SABM` for our
target channel — it occurs during/around the ACL authentication+encryption+SDP+RFCOMM-
multiplexer setup that precedes it, WHILE that setup is still in progress (not after a
timeout genuinely elapsed) — i.e. this looks like a Java-layer `BluetoothSocket` bug
where the internal control-socket signaling used to detect "connect complete" gets a
spurious EOF, not a real rejection by the watch. This is a widely-reported category of
AOSP/OEM Bluetooth stack bug (search "BluetoothSocket read failed ret -1 connect"), not
unique to this project.

**Update after further debugging this session**: tried both `getConnectDelayMillis()`
(500ms) and `createInsecureRfcommSocketToServiceRecord` (added as a new, reusable
`AbstractBTBRDeviceSupport.useInsecureRfcommSocket()` hook + `BtBRQueue.setInsecure()`,
committed) — **neither fixed it**. Also ruled out competing apps (Samsung's
`watchmanager`/`watch7plugin` fully killed via `pm disable-user` AND stripped of
`enabled_notification_listeners` access, which is what had been silently reviving them
after a plain `force-stop` — they're a registered `NotificationListenerService` and
Android auto-rebinds those) and stale Bluetooth stack state (toggled BT off/on for a
clean slate) — failure reproduces identically regardless.

**Crucial new data point**: captured the failure with full system-wide logcat (`-b all`,
including the native `bluetooth`/`BluetoothSocket` process logs, not just Gadgetbridge's
own). One particular attempt showed:
```
18:47:52.687 I/BluetoothSocket(13854): connect(), SocketState: INIT, mPfd: {...}
18:47:55.739 V/BluetoothSocket(13854): inputStream.read ret: 4
18:47:55.740 E/...BtBRQueue: IO exception ... read failed ... ret: -1
```
A genuine **~3 second gap** between issuing connect() and the failure — with an actual
`inputStream.read ret: 4` (4 bytes legitimately read from the control LocalSocket used
for app↔Bluetooth-service IPC) immediately preceding it. This is NOT the same as the
earlier near-instant (~100-300ms) failures seen before adding the connect delay — this
one genuinely waited for and received a real response before failing. **This changes the
diagnosis**: it isn't a spurious client-side race in `BluetoothSocket`'s internal
signaling (which would fail near-instantly or with no real response) — the Bluetooth
*system service* itself waited ~3s (consistent with an actual RFCOMM
connect/negotiation attempt happening) and then reported back a genuine failure/refusal.
Also notable: the failing attempts and a separately-observed successful native-level
data read both referenced RFCOMM channel/SCN **12** — inconclusive on its own (Android
may cache the SDP-resolved channel per UUID+device rather than re-resolving fresh each
time), but suggests channel reuse across attempts rather than a fresh SDP negotiation
each time, worth keeping in mind.

**Leading hypothesis given this**: the watch's RFCOMM server for the DATA_LAYER UUID may
only accept the connection from a client that has first "introduced" itself over the
SAK_INSECURE channel (UUID `0bb62597-f040-42ef-82e4-0314fdfb7478`, from §2/§5c) — i.e.
even though the *cryptographic* SAK/GAK attestation verification itself is skippable for
non-Samsung phones (per watchmanager.apk's own `VerificationManager.isSupportSAKVerify()`
logic, §1), the watch might still expect SOME minimal exchange on that channel (even a
failed/incomplete one) as a signal that a "real" companion client is attempting to pair,
before it grants RFCOMM access to the actual data channel to a previously-unseen
requester. Not confirmed — would need connecting to SAK_INSECURE first (even just
opening/closing the socket, or exchanging a minimal handshake per the `BluetoothInsecureManager`/
`InsecureConnectionCallback` classes in watchmanager.apk, §1) immediately before
attempting DATA_LAYER, then checking if that changes the outcome. This is real
follow-up engineering work, not a quick fix — flagged here for a future session.

**Tried and ruled out this session**: connect delay (500ms), insecure RFCOMM socket,
killing/disabling competing Samsung apps, Bluetooth stack restart, several rounds of
Gadgetbridge's own exponential-backoff auto-reconnect (2s/4s/8s/16s/32s — none of those
spaced-out retries succeeded either).

**SAK_INSECURE hypothesis tested and REJECTED**: pointed `GalaxyWatch7DeviceSupport`
at `0bb62597-f040-42ef-82e4-0314fdfb7478` (SAK_INSECURE) instead of DATA_LAYER as an
experiment — **fails identically** (`read failed ... ret -1`, same near-instant timing
pattern). This rules out "the watch specifically gates the data channel behind a SAK
precursor" — the failure is generic to ANY RFCOMM UUID our app tries to connect to, not
specific to DATA_LAYER. This actually narrows the problem usefully: whatever is
happening is about how/when the connection is attempted in general (this app/client,
or this connection pattern), not about a specific required channel-open sequence.

**Not yet tried** (candidate next steps for a future session, roughly in priority order):
1. Investigate whether the explicit `action.disconnect` that Gadgetbridge's
   `DeviceCommunicationService` issues immediately before every `action.connect` (visible
   in every log excerpt this session) causes the ACL link to actually drop each time,
   forcing a full fresh authenticate+encrypt+SDP+multiplex negotiation per attempt (which
   IS what the raw HCI traces show) — versus Samsung's own app/service, which manages its
   connection more continuously and may attempt RFCOMM on an already-warm, already-
   multiplexed ACL link. If confirmed, avoiding the redundant disconnect (or at least not
   tearing down the ACL) before a fresh connect attempt might matter. This is shared
   framework behavior (`DeviceCommunicationService`), so a fix would need to be
   general-purpose, not Galaxy-Watch7-specific.
2. Test on a different phone/Android build to rule out this being specific to this
   LineageOS 23.2 (Android 16) build's Bluetooth stack (e.g. a stock/GSI build, or an
   older Android version) — would help isolate "our code/protocol understanding" from
   "this specific phone's BT stack" as the variable.
3. Capture the SAME failing attempt with `btsnoop` running AND full system logcat
   simultaneously (this session only managed one or the other per attempt) to line up
   the native RFCOMM/L2CAP events precisely against the ~3s Java-side wait and see
   exactly what (if anything) the watch sends back that gets interpreted as failure.

## 8. BREAKTHROUGH: the RFCOMM connect() failure is fixed

The persistent `BluetoothSocket.connect()` "read failed ... ret -1" failure from §7 is
**solved**. Root cause was never fully pinned down at the Android-internals level, but the
practical fix is simple and reliable: **immediately retry, recreating the `BluetoothSocket`
each time**, rather than waiting for Gadgetbridge's multi-second external auto-reconnect
backoff. The failure is apparently transient/flaky at the native BT stack level — it
almost always succeeds within 1-2 immediate retries (observed consistently across many
runs). Implemented as two new, reusable, opt-in (default-off, zero behavior change for
other devices) hooks on `AbstractBTBRDeviceSupport`:

- `getExtraConnectRetries()` / `getConnectRetryDelayMillis()` — retries the initial
  `connect()` call itself (recreating the socket) up to N times with a short delay, before
  surfacing a failure. Galaxy Watch7 uses 5 retries / 400ms.
- `getFastReconnectBudget()` / `getFastReconnectWindowMillis()` — if the connection
  succeeds but then drops again within the window (see §9), immediately reconnects
  (consuming the budget) instead of deferring to the external backoff. Galaxy Watch7 uses
  a budget of 10 / 1000ms window.

Both live in `BtBRQueue.java` (the shared low-level connect loop) + `AbstractBTBRDeviceSupport.java`
(the hook methods), not in Galaxy-Watch7-specific code, so any other BT Classic device
support hitting a similar flaky-connect issue can opt in the same way.

**Verified against the real device**: the RFCOMM socket connects successfully, our
NodeInfo handshake is written to it, and — confirmed via raw HCI capture — **the watch
correctly parses it and replies with its own valid NodeInfo** (`nodeId="19eaaf8"`,
`name="Galaxy Watch7 (CLMP)"`, `role=2`, `sdk=36`), matching the format documented in §3
exactly. This is a full, correct, bidirectional protocol handshake between Gadgetbridge
and a real Galaxy Watch7 — the reverse-engineered protocol understanding from earlier in
this document is now empirically confirmed correct at the wire level, not just inferred
from passive observation of the stock app.

## 9. Remaining issue: the channel closes ~100ms-2s after the handshake exchange

Not yet solved, but well-characterized. After the successful NodeInfo exchange (§8), the
watch closes the RFCOMM channel again shortly after — confirmed via raw native Bluetooth
stack logs (`RFC_PORT_EVENT_DISC`) to be the **watch actively sending the DISC**, not a
timeout or error on our end. Investigated extensively this session:

- **Not caused by our SeqAck follow-up message**: tested with and without sending the
  field-8 SeqAck message (§3) right after NodeInfo — both cases still get cut short by
  the watch. Verified our SeqAck encoding is byte-correct (decodes cleanly with the
  project's own parser, matches the real captured field8 structure including the
  10-byte two's-complement varint encoding of the `-1` sentinel).
- **Not fixed by fast/immediate reconnection**: gave the connection a budget of 10
  immediate reconnect attempts (recreating the socket + resending the handshake each
  time, no multi-second gap) to test whether several quick back-to-back cycles are
  needed to "settle" into a stable session (as hinted at by early stock-app captures
  showing several closely-spaced NodeInfo exchanges before settling) — exhausted the
  whole budget, cut short every single time, never settled.
- **Sending nothing at all extends the session noticeably**: with `initializeDevice()`
  sending zero bytes (just listening), sessions lasted 500ms-2000ms instead of the
  ~90-150ms seen when sending NodeInfo(+SeqAck) — the watch still spontaneously sends
  its own NodeInfo even with no client message, and the channel still eventually closes,
  but later. This is a meaningful, reproducible difference, not noise.
- **Correlates with Samsung's own `watch7plugin` reactivating mid-session**: in the
  "send nothing" test, `watch7plugin` (Samsung's app) resumed doing its own thing
  (posting to `Notification4Watch7_AM`, writing `wcs_extension/sysui/notification/
  switch/app_settings`) roughly 1 second before our own connection got cut — strongly
  suggesting Samsung's app winning/disrupting a concurrent session, not (only) a
  protocol-level rejection of our client.
- **Samsung's `watch7plugin` reactivates despite every isolation attempt tried**:
  `am force-stop` alone doesn't stick (comes back within seconds). Investigated and
  ruled out two specific persistence mechanisms:
  - `NotificationListenerService` registration (`android.service.notification.
    NotificationListenerService`, `CompanionNotificationListenerService`) — stripped via
    `settings put secure enabled_notification_listeners "<gadgetbridge-only>"`.
  - `CompanionDeviceManager` association (`cmd companiondevice disassociate ...`,
    confirmed removed via `dumpsys companiondevice` — `Bound Companion Applications:
    <empty>` immediately after) — **also confirmed to NOT be the (sole) mechanism**:
    watch7plugin reactivated again in a later test with the CDM association still
    empty. The actual trigger is still unidentified — no manifest `<receiver>` for
    `ACL_CONNECTED`/`ACL_DISCONNECTED` was found in watch7plugin's resolver table
    (`dumpsys package com.samsung.wearable.watch7plugin`), so it's likely either a
    dynamically-`registerReceiver()`'d listener in watchmanager.apk (§1's
    `VerificationManager` has exactly this pattern, though instance-level registration
    shouldn't normally survive force-stop) or a lower-level Bluetooth profile
    auto-connect policy (HFP `connectionPolicy=100` was observed set at the system
    `BluetoothDatabaseManager` level, independent of any app) that indirectly wakes it.

**For a future session**: the most promising next step is getting a genuinely
Samsung-app-free test — e.g. temporarily uninstalling `com.samsung.wearable.watch7plugin`
entirely (APK already saved at `apks/watch7plugin.apk` in this session's scratchpad for
reinstalling afterward) rather than trying to suppress it while installed, to get a clean
signal on whether the channel would stay open without any interference. If it still closes
even then, the next angle is matching the exact `wear://` DataItem/MessageApi traffic
pattern the stock app sends in the first 1-2 seconds after NodeInfo (already catalogued in
§5) and proactively sending some of it (e.g. `wearable/supported/features`, a
`capabilities/...` announcement) rather than staying silent, in case the watch expects the
client to immediately start "behaving like a real companion" rather than just idling.

## 6. Suggested next steps

1. Capture a **normal-use session** (watch worn, phone nearby, trigger a
   notification, let a step/HR sync happen) to see the actual sensor/notification
   DataItem paths and payload shapes Gadgetbridge needs to parse.
2. Nail down the `field 6` (deleted flag?) and `field 5`/`field 10` sequencing
   semantics precisely by diffing a few PUT vs DELETE style updates once real data
   is available.
3. Decide transport approach for Gadgetbridge: implement a minimal Wear-OS
   "companion" role — BT Classic RFCOMM SDP-advertise `DATA_LAYER` UUID
   `5e8945b0-9525-11e3-a5e2-0800200c9a66` on channel 7, speak the framing +
   protobuf described above. `AbstractBLClassicDeviceCoordinator` in Gadgetbridge
   is the right base class (BT Classic, not BLE).
4. For notification forwarding specifically, `wcs_extension/sysui/notification/
   switch/app_settings` + the standard Wear OS notification bridge paths are the
   ones to target first — highest user value, lowest protocol complexity.
5. Optionally decompile watch7plugin.apk's `com.samsung.android.waterplugin.*`
   and `com.samsung.android.companionservice.*` packages for exact proto field
   names (would replace the inferred field numbers above with real semantics) —
   deferred so far due to APK size (180MB / 8 dex files).
