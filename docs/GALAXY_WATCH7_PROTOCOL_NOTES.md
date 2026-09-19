# Galaxy Watch7 <-> Phone Protocol — Reverse Engineering Notes

## STATUS / TL;DR (last updated 2026-09-17, read this first)

**Current understanding (supersedes the pessimistic conclusions in §7-§12):**

- The classic RFCOMM Data Layer channel (UUID
  `5e8945b0-9525-11e3-a5e2-0800200c9a66`, RFCOMM channel 12) **works and is
  the right transport.** A real `watch7plugin` session on it runs for many
  seconds with continuous two-way DataItem traffic (§14). It is NOT a legacy
  stub.
- **Why Gadgetbridge is dropped after ~100 ms:** GB's post-NodeInfo handshake
  is malformed. The real client sends a **SeqAck / peer-sync message
  (payload field 8) containing a full `{nodeId, sequence}` map of all known
  peers** (`cloud`, own node, current watch node, historical nodes) + a
  request-id. GB sends a single stale hardcoded entry (`19eaaf8`, seq 0) plus
  a garbage `0xff…` value, so the watch can't start the sync session and
  disconnects. See §14 for decoded reference bytes.
- **BLE L2CAP PSM path (§8) is a dead end** for us: the PSM GATT service
  `3675c0ff-…` is never exposed to a plain bonded peer, even after a full
  real companion setup (§10, §12). Ignore it; use classic RFCOMM.
- **Pairing:** plain Android `createBond()` is enough; no Samsung
  attestation, no Google account needed (§1). Samsung's Galaxy Wearable /
  watch7plugin apps are NOT required for the transport and actively
  interfere with a third-party client — keep them uninstalled (§6).
- **Captured reference material:** `docs/captures/` holds a real
  watch7plugin↔watch session — the raw btsnoop
  (`watch7plugin_datalayer_session.btsnoop`) and the fully decoded,
  length-prefixed message dump both directions
  (`watch7plugin_datalayer_messages.txt`).

**Fix implemented (§15, 2026-09-17):** all three §14 steps are now in code and
byte-verified against the reference capture. Watch node id is parsed live; the
peer-sync is built as a correct `{cloud,0}/{ownNode,0}/{watchNode,0}` map with
`field8.field1=0` (not the old `0xff…` garbage). **Keep-alive turned out to be
unnecessary** — btsnoop shows an 8.8 s silence gap with no drop and a
phone-initiated final DISC, so the malformed peer-sync alone caused the ~100 ms
drop. Byte-level build spec: `docs/captures/SEQACK_BUILD_SPEC.md`. **Still
untested against the live watch** (phone dropped off adb before a connect could
be triggered) — test procedure at the end of §15.

**How the sections below read:** §1-§6 (protocol/pairing basics) and §13-§14
(the breakthrough) are current. §7-§12 document the multi-hour path that
*wrongly* concluded the channel was a dead stub — kept for the reasoning
trail and the ruled-out alternatives, but their headline conclusion is
overturned by §14. Read §14 as the authority.

---

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

**RESOLVED**: uninstalled `com.samsung.wearable.watch7plugin` entirely (`adb uninstall`;
APK preserved at `apks/watch7plugin.apk` in this session's scratchpad for reinstalling
later if ever needed — `com.samsung.android.app.watchmanager` was left installed, it
was never observed to be the interfering one). With watch7plugin gone:

**The Gadgetbridge connection stayed continuously "Verbunden" (Connected) in the UI for
over 2 minutes straight, with zero connect/disconnect/error log lines in that entire
window** (checked via two consecutive logcat capture windows, 45s then 80s, both showing
0 matching connect/socket/error events). This is a complete, unambiguous confirmation:
**Samsung's own `watch7plugin` was the actual root cause of every quick-disconnect
observed all session** — competing for/disrupting the RFCOMM session via whichever
still-unidentified persistence mechanism kept reviving it (see the ruled-out list above;
the exact mechanism remains unknown, but it no longer matters once the app isn't
installed at all).

**Practical implication for real-world Gadgetbridge usage**: a user pairing a Galaxy
Watch7 with Gadgetbridge will very likely need to **uninstall (or at least keep
force-stopped/disabled) Samsung's Galaxy Wearable / Watch7 plugin apps** for a stable
connection, since both apps are written to treat themselves as the sole/primary
companion and will keep trying to reconnect and interfere otherwise. This should be
called out explicitly in any user-facing setup instructions once this device support
ships.

**Next implementation step**: with the transport now proven rock-solid, the highest-value
next work is wiring up actual functionality — start with the generic boolean-setting
DataItem PUT (`WearDataLayerProtocol.buildBooleanSettingPut()`, already implemented,
never yet exercised against the live connection) via a real Gadgetbridge device-settings
screen (`getSupportedDeviceSpecificSettings()` + an XML preferences resource, following
the pattern other device coordinators use), to get first real confirmation that we can
not just talk to the watch but successfully *change something on it*.

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

## 7. Regression after adding settings-write code (2026-09-16)

After the "rock-solid 2+ minutes" state documented in section 6, a later APK
reinstall (which added the `onSendConfiguration()` / Always-On-Display
settings-write path) came back with the connection dropping again, every
single time, 60-115ms after connect. Re-confirmed watch7plugin/watchmanager
are still uninstalled on the phone (`pm list packages` — no matches), so this
is **not** a repeat of the section 6 cause.

**Findings from this investigation round:**

- Ruled out, phone-side: GMS/Play Services contention (`pm disable-user
  com.google.android.gms` — no change), stale BT bond (fresh unbond/re-pair —
  no change), host BT stack staleness (`svc bluetooth` restart, full phone
  reboot — no change).
- The watch's NodeInfo reply (111 bytes) is **byte-for-byte identical across
  every attempt** observed — spanning hours, an app restart, GMS
  disable/enable, a BT stack restart, a fresh bond, and a full phone reboot.
  This includes fields that should vary (a 27-char request-id-shaped string).
  This is a cached/static reply, not a live per-session response.
- Connected directly to the watch via wireless adb. `dumpsys bluetooth_manager`
  confirmed the bond is intact and symmetric (watch's bonded-device MAC
  matches our phone's BT MAC exactly; the "Mi 10T" name shown is just a stale
  cached label, not a different device).
- **On the watch itself**, `com.google.android.gms` (PID varies, respawns
  ~1s after any `force-stop` — it's a protected system process, same as
  observed on the phone) registers an RFCOMM listener on the **exact UUID**
  Gadgetbridge uses (`5e8945b0-9525-11e3-a5e2-0800200c9a66`, SDP service name
  `WearableBt`) — this is Google's own native Wear Data Layer server, not
  anything Samsung-specific. That UUID is genuinely the standard Wear OS BT
  Classic data-layer transport, owned by GMS on the watch side.
- With GMS on the watch freshly force-stopped (it respawns before the test
  window completes, so this only tests "just-restarted GMS", not "GMS truly
  absent" — a clean negative test isn't possible since it's a protected
  process): triggered an explicit connect via UI tap on the device card
  (`DeviceCommunicationService` action `connect`, not just relaunching the
  app — relaunching alone does **not** trigger an automatic reconnect
  attempt, contrary to earlier assumption). Full RFCOMM handshake completes,
  Gadgetbridge sends its NodeInfo + SeqAck, and **this time the watch's
  111-byte NodeInfo reply is actually received end-to-end** (first
  confirmed real payload receipt this session). But: the socket is closed
  by the remote side (`IOException: bt socket closed, read return: -1`)
  consistently **~15ms after the reply is received, ~90-134ms after
  connect** — every fast-reconnect cycle, repeatedly. The reply payload
  content is identical to what was seen in every earlier capture.

**Working conclusion**: raw app-level RFCOMM against this UUID gets accepted
and receives one static/cached NodeInfo-shaped reply, then is deliberately
closed by the watch side — most consistent with the connection being
terminated by GMS's own Wear Data Layer service because Gadgetbridge never
completed whatever additional handshake/authentication that service expects
from a genuine paired Wear OS companion (this app was never taken through
Google's real phone↔watch companion pairing flow, only a raw BT Classic
bond). The static reply may be a generic/cached "hello" the service always
sends before checking companion validity and hanging up. Not yet proven —
next step would be checking watch-side logs for the GMS-owned RFCOMM
accept/close pair with full context (uid/session identifiers) at the moment
of drop, which wasn't isolated cleanly in this round because GMS's respawn
timing couldn't be controlled precisely enough for a clean A/B test.

## 8. Decompiling watch7plugin.apk — dual transport discovery (2026-09-17)

Per user request, pulled `com.samsung.wearable.watch7plugin` (v2.2.15) and
`com.samsung.android.app.watchmanager2` (v2.2.70) APKs (via APKMirror, since
the test phone — a LineageOS ROM on Mi 10T hardware, no Google account — had
no way to get them from Play Store; there is no second real phone, the "Mi
10T" name shown in the watch's bonded-device list earlier was simply this
same test phone's stock BT device name, not a stale/different device). Both
pulled to `apks/` locally (untracked, not committed — too large for git).

watch7plugin.apk is 180MB / 8 dex files, mostly obfuscated (R8/ProGuard,
single/double-letter class names). Located the relevant code by grepping raw
dex bytes for our UUID and protocol keywords rather than decompiling
everything:

- `com/samsung/android/companionservice/connection/bluetooth/util/
  ConnectionUtil.java` (classes5.dex, **not obfuscated** — Samsung's own
  code) confirms `DATA_LAYER = 5e8945b0-9525-11e3-a5e2-0800200c9a66`, plus
  two more UUIDs used for a "SAK" (Setup Assistant Kit?) setup-mode
  handshake: `SAK_SECURE = 0aa62597-f040-42ef-82e4-0314fdfb7478` and
  `SAK_INSECURE = 0bb62597-f040-42ef-82e4-0314fdfb7478`. Critically,
  **Samsung's app does not talk raw sockets itself** — it goes through
  `com.google.android.libraries.wear.companion.*`, Google's official OEM
  Wear Companion library, which is bundled in the APK (classes3.dex) as
  `com.google.android.gms.internal.wear_companion.*` (obfuscated,
  e.g. class `Ov` = `BluetoothConnectionManager`).

- **`Ov` (BluetoothConnectionManager) runs two transport mechanisms in
  parallel, not one**:
  1. Classic BT RFCOMM, SDP service name `"Flow15"`, on the configured
     `DATA_LAYER` UUID — split into a "legacy" job
     (`produceLegacyRfcommSocketJob`) and a normal job
     (`produceRfcommSocketJob`), both always started.
  2. **A BLE-based L2CAP CoC channel** (`listenUsingL2capChannel()`,
     internal flag name `"l2cap_sysproxy_server"`) via a `PsmGattManager`
     component, started **only when `Build.VERSION.SDK_INT >= 35` AND** a
     server-supplied `enableBleProxy` configuration flag is set
     (`produceBleSocketJob`).

  The watch (`ro.build.version.release` = 16, i.e. Android 16 / SDK 36)
  qualifies for the BLE path. **Working theory: on a modern Wear OS device,
  the real, live Data Layer session runs over the BLE L2CAP proxy channel,
  and the classic RFCOMM path is kept alive only as a legacy/compatibility
  stub** — accept the connection, send one static capability/identity
  reply, then close, exactly matching every observation from section 7
  (byte-identical reply, deterministic ~15ms-after-reply close, unaffected
  by any phone- or watch-side reset). This is not proven from the
  decompiled code alone (the actual accept-loop method, `zzfsu.
  invokeSuspend`, is a coroutine state machine too complex for jadx to
  fully decompile — "Regions stack size limit reached" — smali-level
  tracing would be needed to nail this down further), but it is the most
  parsimonious explanation that fits every data point gathered so far, and
  it reframes the whole effort: **a classic-RFCOMM-only implementation
  (which is all `AbstractBTBRDeviceSupport`/`BtBRQueue` in Gadgetbridge
  currently supports) may be structurally unable to get a real, sustained
  session with a modern Galaxy Watch7** — the real traffic may require
  implementing the BLE L2CAP CoC transport instead (or in addition).

**Not yet done**: confirming this theory needs either (a) capturing the
watch-side `l2cap_sysproxy_server`/GATT PSM negotiation while a real
Samsung phone + Watch7 pair talk to each other (would need a live A/B
reference capture, not available with only this one dev watch), or (b)
smali-level tracing of `zzfsu`/`zzfsy` to find the actual PSM value and
characteristic UUIDs used to bootstrap the L2CAP channel, then testing
whether Gadgetbridge can open that channel directly via Android's
`BluetoothDevice.createL2capChannel(psm)` / `listenUsingL2cap()` APIs
(added in Android 10, no special permission beyond BLUETOOTH_CONNECT).

**Update**: (b) is now mostly done. Found and read
`com/google/android/gms/internal/wear_companion/C0602aw.java` (implements
the `Kv`/`psmGattManager` interface referenced from `Ov`). The mechanism is
fully concrete:

- GATT **service** UUID `3675c0ff-21a6-4f96-9984-09d4f10c64f9`
- GATT **characteristic** UUID `c8625a0b-28c8-4460-874d-0cf9a7538b46`
  (inside that service)

Flow: the phone opens its own L2CAP CoC server socket
(`listenUsingL2capChannel()`, which lets Android auto-assign a free PSM),
then `connectGatt()`s to the watch over BLE (transport `TRANSPORT_LE`,
autoConnect=true), finds that service/characteristic on the watch's GATT
server, and **writes** a small protobuf message (`C1623sc`, 3 int-ish
fields — one of them is clearly the PSM number, the meaning of the other
two, one of which is always literal `99999`, is not yet determined) into
it — telling the watch which PSM to dial back on to open the actual L2CAP
data channel. (Whether the reverse also happens — watch publishing its own
PSM for the phone to dial as client — wasn't checked; `C0602aw` here only
shows the phone-as-L2CAP-server / phone-writes-characteristic direction.)

This confirms a full second BLE-based protocol layer really exists and is
concrete enough to eventually reimplement, but it's a substantial chunk of
new work (BLE GATT connect/discover/write, the `C1623sc` protobuf shape,
L2CAP CoC socket handling — none of which overlaps with Gadgetbridge's
existing BT-Classic-only `AbstractBTBRDeviceSupport`/`BtBRQueue`
framework) — **paused here rather than continued blind**, pending a
decision on whether it's worth pursuing before doing anything productive
with the classic-RFCOMM path (see next section for the current pragmatic
test instead).

## 9. Pragmatic test: does the ~100ms classic-RFCOMM window accept a real write?

Rather than continuing to reverse-engineer the BLE path, tested whether the
already-working classic RFCOMM connection — despite being closed by the
watch ~100-130ms after every connect — is long enough to get a real,
acted-upon settings write out. Observed captures show our own outbound
writes (NodeInfo handshake, SeqAck) reliably complete in ~1-2ms each once
the socket is open, well within the ~90ms window before the watch's reply
arrives and the ~15ms after that before it closes. So there is a real
window to fit one more small write.

Change made (temporary, for this test only): `initializeDevice()` now also
queues the Always-On-Display `DataItem` PUT (using the current preference
value) as part of the same initial 3-action transaction, immediately after
the NodeInfo handshake + SeqAck, instead of only sending it reactively from
`onSendConfiguration()` when the user toggles the switch in the UI (which
is very unlikely to land inside a ~100ms window by chance). This makes
every single fast-reconnect cycle attempt the write automatically, without
needing precise manual timing.

**Result: negative, but clean and conclusive.** Built + installed this
version, confirmed via phone logcat that the write completes successfully
(`Action ok: WriteAction`) on essentially every fast-reconnect cycle, for
both `enabled=true` and (after toggling the device-settings switch back
off) `enabled=false` — several times each, sent seconds apart. Checked the
watch's own Settings → Display → Always On Display toggle after each
(force-stopping `com.google.android.apps.wearable.settings` between checks
to rule out stale/cached UI) — **it stayed ON the entire time, regardless
of which value Gadgetbridge sent, including several sends of `false`
after it was confirmed on.** The watch never changed state in response to
either value.

This settles the open question from section 8: the classic RFCOMM "legacy"
channel on this watch accepts a connection, exchanges its static
NodeInfo/SeqAck handshake, accepts (does not reject/error on) an arbitrary
DataItem PUT write, and then closes — but **does not act on the DataItem
content at all**. It's a stub/compat shim, not a functioning settings
channel. Real settings control almost certainly requires the BLE L2CAP
proxy channel described in section 8 (GATT service
`3675c0ff-21a6-4f96-9984-09d4f10c64f9` / characteristic
`c8625a0b-28c8-4460-874d-0cf9a7538b46` PSM handshake, then an L2CAP CoC
data channel) — or possibly the full Google account-linked companion
pairing flow, which is a separate question from transport alone.

**Reverted**: the `initializeDevice()` change that piggybacks the
Always-On-Display write has been left in place in the working tree for
now (harmless — it's a fire-and-forget write the watch ignores) but should
be reverted before any commit, since it conflates device init with an
unrelated settings push and the experiment is concluded.

**Bottom line for next steps**: further work on Galaxy Watch7 support
needs a decision on scope — either (a) invest in implementing the BLE
L2CAP proxy transport (concrete UUIDs known, PSM handshake protobuf shape
partially known, L2CAP CoC socket handling needed — a genuinely new
transport layer, no overlap with Gadgetbridge's existing BT-Classic-only
framework), or (b) treat classic-RFCOMM Watch7 support as capped at
"connect + identify" with no real settings/notification functionality
achievable this way, and look for a fundamentally different integration
point.

## 10. BLE GATT reachability probe — the PSM service isn't exposed to us (2026-09-17)

Before committing to the large effort in (a) above, did the cheapest
possible check first: added a one-shot, read-only diagnostic
(`probeBleDataLayerGatt()` in `GalaxyWatch7DeviceSupport`, fired once from
`connect()`) that does a plain `BluetoothDevice.connectGatt(..., TRANSPORT_LE)`
against the watch and dumps whatever GATT services it advertises — no
writes, just discovery, to test reachability before investing in the full
PSM-negotiation protocol.

**Result**: the BLE GATT connection itself works fine (connects,
discovers services in ~1s). But the watch only exposes 5 services:
`00001801-...` (Generic Attribute), `00001800-...` (Generic Access),
`0000180a-...` (Device Information), and two custom ones
(`e73e0001-ef1b-4e74-8291-2e4f3164f3b5`, `eed6d5cc-c3b2-4d7b-8c6b-7acbf7965bb6`
— not investigated further). **The PSM negotiation service
(`3675c0ff-21a6-4f96-9984-09d4f10c64f9`) from §8 is not among them.**

This means the GATT service that would let us negotiate an L2CAP PSM is
not just "there but unauthenticated" — it isn't exposed at all under a
plain classic-BT-bonded-but-not-companion-paired connection. The most
likely explanation: GMS's Wear Companion library only registers/exposes
that GATT service dynamically, gated on the connecting device already
being a recognized, authenticated companion (i.e. having gone through the
real Google-account-linked Wear OS pairing flow — Galaxy Wearable / Wear
OS by Google app, not just a raw BT Classic bond). That flow is backed by
Google's servers and isn't something a reverse-engineered client can
plausibly replicate from the protocol alone.

**This changes the earlier "decision on scope" framing**: option (a) —
implementing the BLE L2CAP proxy transport — is likely not viable at all
via reverse engineering alone, not just "a lot of work", because the
entry point into it (the GATT service) appears to require passing through
Google's real companion authentication first. Realistic paths forward, in
rough order of promise:

1. **Accept the cap**: classic RFCOMM gets you "the watch is nearby and
   bonded" (connect/disconnect state, maybe basic presence), nothing more
   — no settings, no notifications, no real Data Layer functionality.
   Gadgetbridge's Galaxy Watch7 support stays a stub/proof-of-concept.
2. **Investigate whether the GATT service appears after actually
   completing Google's account-linked companion pairing once** (e.g. via
   a real Wear OS/Galaxy Wearable app pairing session), to see if the
   service then becomes visible to any bonded device afterward (not just
   the one that paired) — would need a live test with the real apps
   still installed, which conflicts with §6's finding that they interfere
   with Gadgetbridge's own connection. Unclear if even worth trying.
3. Revisit whether Wear OS exposes *any* other, non-Google-proprietary
   integration surface for third parties on this generation of hardware
   (e.g. Health Connect, notification listener sync via a different
   mechanism, or something at the Tizen/One-UI-Watch layer rather than
   stock Wear OS) — would be a different research thread entirely, not a
   continuation of this protocol reverse-engineering effort.

The BLE probe code (`probeBleDataLayerGatt()` and its call from
`connect()`) has been left in the working tree as a harmless read-only
diagnostic, not reverted — it costs nothing to leave in and documents the
finding directly in code, but should be removed before any real commit
along with the rest of the experimental scaffolding once a direction is
chosen.

## 11. Frida instrumentation of watch7plugin.apk — the real architecture is a TCP tunnel over Bluetooth ("WearProxy") (2026-09-17)

Per user suggestion, used Frida (via `objection patchapk`) to inject
`frida-gadget` into `watch7plugin.apk` (root-less — no device root needed,
just an APK repackage) to observe its real runtime behavior instead of
continuing to infer everything from static/obfuscated decompilation.
Process: `objection patchapk -s watch7plugin.apk -a arm64-v8a -t
com.samsung.android.waterplugin.WaterPluginApplication -V 17.17.0`
(had to specify the Application class explicitly — the app declares no
launchable activity at all, being a plugin meant to be hosted by
`com.samsung.android.app.watchmanager2` / Galaxy Wearable, not launched
standalone; also had to pin the gadget version to match the local `frida`
CLI version, `17.18.0` vs `17.17.0` silently broke the attach protocol).

Directly starting the plugin's activities via `adb shell am start -n
com.samsung.wearable.watch7plugin/...` is blocked (`SecurityException:
... requires com.samsung.android.hostmanager.permission.
ACCESS_UNIFIED_HOST_MANAGER`, or "not exported") for every activity that
does real work — **confirmed by the user's hint** that watch7plugin only
runs when hosted by the actual Watch Manager app. Installed
`com.samsung.android.app.watchmanager2` (pulled from APKMirror alongside
watch7plugin) and drove its setup wizard (Bluetooth/notification/call
permission dialogs) via `input tap` — it immediately recognized the
already-bonded Watch7 under "Gekoppelte Geräte" and, once tapped, launched
watch7plugin's real entry point (`HMLaunchActivity`) on our behalf, now
successfully (proves watchmanager itself is what's normally granted/holds
`ACCESS_UNIFIED_HOST_MANAGER`, not something inherent to the plugin).

**What this revealed, with the plugin actually running (background
processes `:plugin` and `:persistent` stayed alive even though the
foreground UI reverted to the home screen / hit a startup ANR — logcat
kept flowing regardless):**

- `WearInit: ... SecurityException: GoogleCertificatesRslt: not allowed:
  pkg=com.samsung.wearable.watch7plugin ... (go/gsrlt)` followed by
  `WearProxy: [Service]v6.1.9-eap02 Couldn't get watches, error:
  API_UNAVAILABLE` — GMS's official Wearable API rejects our repackaged
  APK because objection re-signed it with a debug key (expected: Google's
  cert pinning check fails on any non-Samsung-signed build). This closes
  off observing the *fully authenticated* path this way, but —
- **A separate, lower-level mechanism kept working anyway**: tagged
  `WearProxy` at the `[BTConnMgr]` level, it accepted an **incoming**
  classic-RFCOMM connection *from the watch* (watch is the client here,
  phone is the server — the reverse of what Gadgetbridge does) on a
  **third UUID**: `fafbdd20-83f0-4389-addf-917ac9dae5b2` — which is
  exactly the previously-unexplained static field `u` in the decompiled
  `Ov` (`BluetoothConnectionManager`) class from §8. Log sequence:
  `[BTConnMgr] rfcomm socket accepted: BC:A0:80:DB:08:07 on fafbdd20-...`
  → `[BTConn:...] Bluetooth node connected` → `[BTNode:...] Bluetooth
  read/write loop started` → `[NetConnMgr] Node connected: [BTNode:...]`.
- Once that Bluetooth "node" connects, **`WearProxy` opens multiple
  numbered virtual TCP channels over it**: `[T:BC:A0:80:DB:08:07@e67a7e1:1]
  TCP OPEN`, `:2 TCP OPEN`, `:3 TCP OPEN`, `:4 TCP OPEN`, `:5 TCP OPEN`,
  interleaved with `TCP CLOSE`. Each one attempts
  `SocketChannelImpl.finishConnect()` to some **local TCP port on the
  phone** and fails with `java.net.ConnectException: Connection refused`
  (call stack: `com.google.android.gms.internal.wear_companion.qw.a` →
  `zzftk.invokeSuspend` → `com.samsung.android.sdk.accessory.l.R` →
  `zzftl.invokeSuspend`) — refused because whatever local service is
  supposed to be listening on that port isn't running in our broken/
  unauthenticated instance.

**Interpretation**: the real Wear Data Layer transport is not a single
flat protobuf-over-RFCOMM envelope channel the way Gadgetbridge's current
implementation (and this document's earlier sections) modeled it. It's a
**generic multiplexed TCP-over-Bluetooth tunnel** ("WearProxy") — the
watch opens a classic RFCOMM connection *to the phone* on UUID
`fafbdd20-83f0-4389-addf-917ac9dae5b2`, and tunnels arbitrary TCP
connections through it, each addressed to some local port on the phone
side. This looks structurally like the well-known Wear OS "Bluetooth
internet proxy" feature (letting a watch without its own connectivity
route TCP traffic through the paired phone) — and quite plausibly the
*same* tunnel (or an equivalent one) is how the actual Data Layer
settings/notification sync traffic reaches whatever local service
implements it, i.e. what we've been calling "the Data Layer protocol"
(NodeInfo/SeqAck/DataItem on UUID `5e8945b0-...`) may be a distinct,
narrower, possibly legacy/compat channel, while the *real* modern
traffic — and almost certainly the BLE L2CAP PSM path from §8 too — rides
inside this TCP tunnel concept instead.

This reframes the whole investigation once more: there isn't just "one
alternate transport" (BLE L2CAP) to consider — there's a whole
tunnel/proxy layer, on both classic RFCOMM and BLE, that the real
companion software implements and Gadgetbridge's current architecture has
no analog for at all.

**Not done / blocked**: going further (seeing what's actually inside
those tunneled TCP connections — likely where real settings/notification
traffic would appear) needs the GMS certificate check bypassed, since
nothing meaningful flows until watch7plugin authenticates as a real
companion. This is a well-known but nontrivial Frida technique (hooking
`GoogleCertificatesRslt`/package signature verification to return
success) — not attempted yet. Also worth doing: hook
`com.samsung.android.sdk.accessory.l.R` / `qw.a` directly to log the
actual target host:port each `SocketChannelImpl.connect()` call is aiming
at, which would show immediately what local service this is supposed to
bridge to, without needing the cert bypass at all — cheaper next step
than the full bypass.

**Paused here** — this is a deep enough rabbit hole, with enough new
information, that it warrants a explicit decision with the user on
whether to keep pulling this thread (cert bypass + connect-target
logging) or stop and use everything gathered so far (§7-11) to make the
scope call this document has been building toward since §9.

## 12. Decisive confirmation: real companion setup doesn't change the classic-RFCOMM behavior (2026-09-17)

After the factory reset (below), the user asked to go all the way: install
watchmanager + watch7plugin (original, unpatched — not the Frida-debug-
signed build), let the *real* companion setup actually complete, then
remove both apps again and see whether the watch stays in a properly
paired state usable standalone.

**Setup completed successfully** — no Google/Samsung account login
required, watchmanager's full "Uhreneinstellungen" (watch settings) UI
came up with working sections (Benachrichtigungen, Anzeige, Health, Pay,
Sicherheit, Konten, Erweiterte Funktionen), confirming a genuine,
fully-authenticated companion pairing this time (unlike the Frida-patched
attempt in §11, which failed GMS's certificate check). Both apps were
then uninstalled; the classic BT bond survived, and the watch left its
stuck "check your phone" screen and returned to normal standalone
operation.

**Gadgetbridge reconnect test, immediately after**: same exact pattern as
every single earlier attempt in this document — one real NodeInfo reply
(112 bytes) received, then `IOException: bt socket closed, read return:
-1` roughly 50ms later, fast-reconnect loop kicks in. A UI screenshot
happened to land in the brief "Verbunden" window and looked like a
breakthrough, but the log makes it clear it's the same ~50-110ms
connect/reply/disconnect cycle as always.

**This is the decisive result the whole investigation (§7-11) was
building toward**: whether or not the watch has ever been through a real,
fully-authenticated Galaxy Wearable companion pairing makes **no
difference whatsoever** to the classic-RFCOMM channel's behavior. This
rules out "incomplete/unauthenticated pairing" as the cause once and for
all. The classic RFCOMM Data Layer channel (UUID `5e8945b0-...`) is
conclusively a legacy/compat stub on this hardware+software combination,
full stop — real functionality lives elsewhere (almost certainly the BLE
L2CAP tunnel from §8/§11, which was never actually tested end-to-end
since the GATT PSM service wasn't reachable in that round). Any future
work on real Galaxy Watch7 functionality (settings, notifications) must
target that transport, not this one.

**Correction after an unrelated watch factory reset** (user reset the
watch mid-session after a stuck Wear Companion setup screen; classic BT
bond was lost and re-paired from scratch via plain Android Bluetooth
settings — no Galaxy apps needed, confirming §6's point that raw BT
Classic pairing is sufficient for Gadgetbridge regardless of Samsung/
Google companion apps). Post-reset, the watch generated a fresh node id
and its NodeInfo reply changed accordingly (83 bytes, node id `efb3c185`,
vs. the previously-always-seen 111-byte reply with node id tied to the
pre-reset pairing) — **so the reply is not perfectly static/cached as
earlier sections assumed, it's tied to the watch's current pairing/node
state**, which still changes once per pairing lifetime rather than per
connection attempt. The core finding stands: one real reply, then the
channel is closed by the watch ~110ms after connect, every time,
regardless of pairing freshness.

**Follow-up attempt**: tried to get the actual `SocketChannel.connect()`
target address (cheaper than a full cert bypass) via a second Frida hook
(`SocketChannel`/`SocketChannelImpl`/`Socket` `.connect()` — see
`hook2.js`). Confirmed the exact component name:
`com.samsung.wearable.watch7plugin/com.google.android.libraries.wear.
companion.proxy.service.WearProxyService`. But reproducing the live
`WearProxy` traffic from §11 turned out to need the *specific* code path
where watchmanager's UI passes the selected device explicitly (intent
extras) — a plain background/StartupListener-triggered start of the same
service immediately logs `Couldn't get watches, error: API_UNAVAILABLE`
and gives up before touching Bluetooth at all, since it has no device
context and the (broken, due to our re-signed APK) GMS API can't supply
one. Re-driving the watchmanager UI to reproduce it hit a wall: **the
classic BT bond between phone and watch disappeared entirely**
(`dumpsys bluetooth_manager` → `Bonded devices: 0`) partway through this
round of testing — exact cause not identified, most likely fallout from
the repeated app force-stops/reinstalls and the two host apps' own
bond-management logic (see §6 for a previously-seen case of an app
silently re-triggering bond changes). Re-pairing would need physical
interaction at the watch and is a reasonable point to stop for this
session.

## 13. Gadgetbridge as WearProxy server: the watch connects in (2026-09-17)

- Re-ran the BLE GATT probe after the real companion setup (§12): still only
  5 services, PSM service `3675c0ff-...` still absent. The two custom services
  were looked up: `eed6d5cc-c3b2-4d7b-8c6b-7acbf7965bb6` is
  `AUTO_CONNECTION_MODE_UUID` in watchmanager's `BluetoothUuidUtil` (a marker,
  not a data channel); `e73e0001-ef1b-4e74-8291-2e4f3164f3b5` is referenced by
  neither app. BLE path stays closed.
- Replaced the BLE probe with a read-only RFCOMM **listener** probe
  (`startWearProxyListenerProbe()`): `listenUsingRfcommWithServiceRecord` on
  the WearProxy UUID `fafbdd20-83f0-4389-addf-917ac9dae5b2`, accept, hexdump.
- **Result: the watch connects to Gadgetbridge's listener within ~3 s of it
  opening, and keeps reconnecting every ~2-5 s** (5 accepts in 20 s) — with
  watch7plugin/watchmanager uninstalled. The watch sends **zero bytes** and
  closes each connection after 0.05-3 s. So after a real companion setup the
  watch actively seeks the phone on this channel; the phone is expected to
  speak first.
- Next: capture the phone's opening bytes from a real watch7plugin session
  via Android HCI snoop log (no root or APK patching needed), then replay
  them from Gadgetbridge.

## 14. BREAKTHROUGH: the classic RFCOMM channel is NOT a stub — GB's handshake is incomplete (2026-09-17)

Enabled `adb root` (phone is a `userdebug` build) + full HCI snoop
(`persist.bluetooth.btsnooplogmode full`), captured a **real watch7plugin
session** on the *same* UUID Gadgetbridge uses (`5e8945b0-...`, RFCOMM
channel 12), decoded with tshark.

**This overturns sections 7-12.** The watch7plugin session on
`5e8945b0-...` ran for **13 seconds** (02:16:46.834 SABM → 02:16:59.051
DISC) with **272 length-prefixed messages phone→watch and continuous
DataItem traffic both ways** — not a 100 ms stub. The classic RFCOMM Data
Layer channel is fully functional. The reason Gadgetbridge gets dropped
after ~100 ms is that **its handshake is malformed**, so the watch tears
the session down.

Decoded opening sequence (each message = 4-byte big-endian length prefix +
protobuf):

- **P0 phone→watch = NodeInfo** (same shape GB sends): node id, name,
  sdk level, a request-id string.
- **P1 phone→watch = SeqAck / peer-sync (payload field 8)** — THE missing
  piece. It is a **full peer sequence map**: a repeated list of
  `{nodeId, sequenceNumber}` for every peer the phone knows —
  `cloud`, the phone's own node, the watch's *current* node id, and
  several historical node ids — plus a request-id. Decoded:
  `field8{ field1=1856, [ {"cloud",2635}, {"a980b4",605},
  {"efb3c185",1144}, {"e1b1cd8b",20127}, {"6e64a622",1779},
  {"8de0a095",2506}, {"1bce196f",3776}, {"b35b35d6",3037},
  {"19eaaf8",6560} ], reqid, f3=1,f4=1,f5=3 }`.
- Then P2, P3, … = a stream of DataItem PUTs
  (`com.google.android.gms → wear://<node>/peers`,
  `.../trusted_packages`, `com.samsung.wearable.watch7plugin →
  wear://<node>/settings/home_info`, `/bridge_mode/...`,
  `/protogensettings/...`, etc.).
- The watch answers symmetrically: W0 NodeInfo, W1 its own peer-sync map,
  W2/W3 its DataItem PUTs (`wear://<watchnode>/peers`,
  `com.samsung.android.wcs.extension → w_callservice/notification...`).

**Contrast with Gadgetbridge's SeqAck** (from GB's own logs):
`field8{ field1=0xffffffffffffffffff01(garbage), field2={"19eaaf8",0},
field3=2, reqid, f3=1,f4=1 }` — a single peer entry, the stale hardcoded
`EXPERIMENT_WATCH_NODE_ID = "19eaaf8"`, sequence 0, and a bogus field1.
The watch cannot establish the sync session from that and disconnects.

**Reference bytes captured (in scratchpad, snoop `snoop1.log`):**
```
P0 NodeInfo:  0a3b3a390a08653162316364386212064d692031305418dac5e5acea91f4873e2003280130003a13343933353737363939343437363034343938335824121b746f6d4b5649463342786b6777344471723063524369642d66464118012001
P1 peer-sync: 0a890142860108c00e120a0a05636c6f756410cb14120b0a0661393830623410dd04120d0a08656662336331383510f808120d0a086531623163643862109f4e120d0a08366536346136323210f306120d0a08386465306130393510ca09120d0a08316263653139366610c00e120d0a08623335623335643610dd0b120c0a073139656161663810a0191802121b396a6f7055564261547a6347727a4e484f5f35646265577a36334d180120012803
W1 peer-sync: 0a5d425b089e4e120a0a05636c6f756410cb14120d0a08656662336331383510f405120d0a086531623163643862109e4e120d0a08366536346136323210f306120d0a08316263653139366610c10e120c0a073139656161663810a0191802121b79396172537a79516576522d6e5169614c7a415f6a447449714767180120012803
```

**Actionable implementation path (finally concrete):**
1. Parse the watch's NodeInfo reply (W0) to learn its *current* node id
   dynamically (drop the hardcoded `19eaaf8`).
2. Build the SeqAck/peer-sync (payload field 8) as a proper repeated
   `{nodeId, seq}` map — at minimum `{cloud,0}`, `{ownNode,0}`,
   `{watchNode,0}` — with a real request-id, not the `0xff…` garbage.
3. Respond to the watch's DataItem PUTs / peers announcements to keep the
   session alive, then the channel should stay up like watch7plugin's.

This is the first path that could plausibly yield a *sustained* session
and real settings/notification exchange over the transport GB already has.

---

## 15. FIX IMPLEMENTED (2026-09-17)

The §14 path is now implemented in code and byte-verified against the reference
capture. A dedicated byte-level build spec lives in
`docs/captures/SEQACK_BUILD_SPEC.md` (annotated hex, field tables, worked frame).

### What changed
- **`WearDataLayerProtocol.java`**
  - `parseWatchNodeId(byte[])` + a minimal `ProtoReader`: reads the watch's live
    node id from its NodeInfo reply (envelope field 1 → payload field 7 →
    NodeInfo field 1, a length-delimited ASCII string). Strips the 4-byte length
    prefix if still present. Drops the hardcoded `19eaaf8`.
  - `buildSeqAck(ownNodeId, watchNodeId)` rewritten to emit the correct
    peer-sync: `field8{ field1=varint(0), {cloud,0}, {ownNode,0}, {watchNode,0},
    field3=2 }`. The old code's `field1 = 0xffff…` sentinel + single stale node
    was the bug — that value was a mis-parse; field1 is really the sender's
    sequence counter *for the recipient* (0 for a fresh session).
  - `wrapEnvelope(payload, withFlagC)`: NodeInfo envelopes carry no field 5;
    peer-sync and DataItem envelopes end with `field5 = 3` (`28 03`). requestId
    (field 2) and the 1/1 flags (fields 3/4) sit on the **outer** envelope, not
    inside field 8 — corrects the earlier §14 mis-parse.
- **`GalaxyWatch7DeviceSupport.java`**
  - Learns + caches the watch node id (per-device pref `galaxywatch7_watch_node_id`).
  - First connect: send NodeInfo → on the watch's NodeInfo reply, parse its id
    and send the peer-sync. Reconnects with a cached id send the peer-sync
    eagerly right after NodeInfo (mirrors the stock app's ordering).
  - Removed the hardcoded `EXPERIMENT_WATCH_NODE_ID` and the `EXPERIMENT_SEND_NOTHING`
    branch; the boolean-setting PUT now targets the learned node id.

### Correction to §14 point 3 — keep-alive is NOT required
btsnoop analysis of the reference session (DLCI 0x18): an **8.8 s gap of total
silence with no disconnect**, and the session's final DISC was **phone-initiated**
(graceful), not a watch-side drop. So the ~100 ms drop is caused *solely* by the
malformed peer-sync. Minimum to survive past ~1 s: valid NodeInfo → parse watch
id → valid peer-sync → keep reading. No app-level acks, no DataItem, no periodic
ping needed. (Answering the watch's DataItem PUTs is still desirable for real
feature exchange, but not for staying connected.)

### Build + test status
- Builds clean: `./gradlew :app:assembleMainlineDebug` (BUILD SUCCESSFUL).
- `buildSeqAck` output verified byte-exact against the spec's worked 82-byte frame.
- **Not yet tested against the live watch** — the test phone dropped off adb
  before a connection could be triggered. Test procedure: see below.

### Test procedure (once the phone is back on adb)
```
adb install -r app/build/outputs/apk/mainline/debug/app-mainline-debug.apk
adb logcat -c
adb logcat -v time GalaxyWatch7DeviceSupport:V WearDataLayerProtocol:V \
  AbstractBTBRDeviceSupport:V BtBRQueue:V '*:S' &
# trigger connect (watch MAC BC:A0:80:DB:08:07):
adb shell am broadcast -a nodomain.freeyourgadget.gadgetbridge.BLUETOOTH_CONNECT \
  --es EXTRA_DEVICE_ADDRESS BC:A0:80:DB:08:07
```
Success = channel stays up well past ~1 s (no ~100 ms drop), `learned watch node
id …` appears in the log, and the socket keeps reading the watch's DataItem PUTs.

---

## 16. Enrollment capture + why standalone GB still fails (2026-09-18)

Re-installed Samsung watch7plugin + watchmanager2, ran the full companion setup
once (enrollment), captured the resulting Data Layer session, then uninstalled
them again and tested Gadgetbridge against the now-enrolled watch.

### New ground truth from the enrolled session
- **Enrolled phone node id = `e1b1cd8b`** (a GMS Wear node id, stable; identical
  to the original reference capture and survives the watch factory-reset).
- **Watch node id changed to `1cf38db4`** (was `1bce196f`) after enrollment.
- **NodeInfo connection id (field 7) = `4935776994476044983`** — same value both
  sides, stable across resets.
- **peer-sync field8.field1 = `0xffffffffffffffff`** for a fresh session (BOTH
  sides send it), NOT `0`. The original GB code's `-1` was accidentally right for
  this field; the "fix" to `0` in §15 was wrong. Corrected back to `-1`.
- No crypto challenge anywhere on the wire — after NodeInfo the watch immediately
  streams DataItems (observed: real German quick-settings JSON: "Nicht stören",
  "WLAN (automatisch)", "Wassersperre", "Offline-Modus", ...). So trust is by
  node identity, not a handshake secret.

### What was tried in GB (all still dropped ~40-90 ms after the watch's NodeInfo)
- Correct NodeInfo incl. field 3 (nonce) + field 7 (connection id echoed).
- peer-sync field8.field1 = 0xffffffffffffffff.
- **Impersonating the enrolled node id `e1b1cd8b`.**
- Eager (bundled) vs reply-driven peer-sync ordering.
- Correct current watch node id (`1cf38db4`, learned live).
- Confirmed GB connects on the SAME RFCOMM channel as GMS (scn 7 / dlci 0x0e);
  channel number is dynamic per boot and is not the issue.

### Conclusion — two compounding blockers
1. **GMS Wear owns the bonded watch.** `com.google.android.gms` (Wear_Transport)
   auto-connects the bonded watch's Data Layer channel on its own, even with the
   Samsung apps uninstalled. Only one Data Layer RFCOMM connection exists; GB and
   GMS fight over it and both get dropped (observed: GB drop + `Wear_Transport ...
   read return: -1` in lockstep).
2. **GB's app-level handshake alone is not sufficient.** Even with GMS momentarily
   force-stopped and GB presenting the trusted identity, the watch still dropped
   GB within ~90 ms, whereas GMS on the identical node id/channel sustains and
   streams settings. GMS clearly performs additional connection-layer negotiation
   (the small control frames seen on the mux, credit-based flow control, and/or a
   connection-setup exchange) beyond the NodeInfo/peer-sync/DataItem envelope we
   reversed. That lower layer is inside GMS core (`com.google.android.gms`), not in
   the Samsung app, and is not yet reversed.

### Implication for the "no wear manager" goal
The reversed envelope protocol (NodeInfo / peer-sync / DataItem, incl. the
settings-PUT format) is correct and confirmed against a live enrolled watch.
But a standalone GB companion over plain RFCOMM needs to (a) stop GMS Wear from
claiming the watch, and (b) replicate GMS's connection-management layer. Both are
substantial; (b) requires reversing the GMS Wear bluetooth transport, not just
the app data protocol. This is the current wall.

---

## 17. Blocker 2 root cause — Fast Pair secret handshake (VERDICT, 2026-09-18)

Decompiled GMS core (`com.google.android.gms` 26.32.34, base.apk classes12.dex)
and traced the Wear transport's connection handshake (`WearableTransport`,
`Wear_Transport`, `gms/wearable/node/TransportWriter`, obfuscated handshake class
`defpackage/gntt`).

The Data Layer transport is gated by a **Fast Pair secret handshake over GATT**:
- `gntt.a(byte[])`: a 16-byte input → "using the given secret" (a stored Fast
  Pair **account key**); a 64-byte input → **ECDH** (EC P-256 public key) with the
  device anti-spoofing key. Imports `com.google.android.libraries.bluetooth.fastpair`.
- `gntt.d()` writes/reads "handshake packet" bytes over a **GATT characteristic**
  (short 0x1234), i.e. this is the BLE side we earlier wrote off as a dead end (§8)
  — it is actually the auth channel.
- Log strings: "generates key by ECDH", "Handshake challenge/confirmation",
  "Handshake verification failed. Invalid ACK code", "Connection handshake
  complete", "GATT_CONNECTION_AND_SECRET_HANDSHAKE".
- The 16-byte account key is **Google-account-bound**: stored in
  `com.google.android.gms/shared_prefs/fastpair_common_preferences.xml` +
  account "footprints" (`FPDC: ... already paired with the account`).

**Verdict:** the secret is NOT derivable from the plain Bluetooth bond. It is a
Fast Pair account key (or ECDH with a Google-held anti-spoofing key) established
during the Google-account-bound enrollment and stored inside GMS. The watch's
RFCOMM Data Layer only proceeds for a peer that has completed this GATT secret
handshake — which is why every GB attempt (even impersonating the enrolled node
id `e1b1cd8b`, correct NodeInfo/peer-sync, correct channel) is dropped ~40-90 ms
after the watch's courtesy NodeInfo.

### Feasibility of a standalone Gadgetbridge companion
Not feasible cleanly. The only theoretical paths:
1. Root-extract the 16-byte Fast Pair account key from GMS for this watch AND
   reimplement the full Fast Pair GATT secret handshake + the authenticated Wear
   transport in Gadgetbridge. Very large, fragile (account-bound, rotates), and
   depends on GMS internals.
2. Google-side keys / official enrollment — not available to third parties.

The reversed **application** protocol (NodeInfo / peer-sync / DataItem, incl. the
settings-PUT and notification formats) remains correct and is confirmed against a
live enrolled watch — but it rides on top of a transport that Gadgetbridge cannot
authenticate to without GMS's Fast Pair account key. This is the wall for the
"companion without Galaxy Wearable / watch7plugin" goal for Data-Layer features.

---

## 18. CORRECTION to §17 — the RFCOMM Data Layer is NOT crypto-gated (2026-09-18)

Deeper inspection of GMS storage and the transport code retracts the §17 verdict:

- `com.google.android.gms/databases/connectionconfig.db` → table
  `connectionConfigurations` has a per-connection `crypto` column, BUT for the
  watch's row it is **empty** (connectionType=5 = Bluetooth/RFCOMM, role=1,
  nodeId=1cf38db4, packageName=com.samsung.wearable.watch7plugin, crypto NULL).
- The Wear RFCOMM transport classes (obfuscated glis/gjxs/glyc = "Wear_Transport")
  contain **no** Cipher/encrypt/AES/HMAC references — the RFCOMM path is not
  encrypted.
- "Connection handshake in progress/complete" are just states of
  `ConnectionStateEventParcelable`, not a crypto handshake.
- The ECDH / Fast Pair account-key handshake (obfuscated `gntt`, imports
  `libraries.bluetooth.fastpair`) is the **Fast Pair** flow for BLE accessories
  (earbuds), NOT the watch's RFCOMM Data Layer. §17 conflated the two.

So there is most likely **no cryptographic wall** on the RFCOMM Data Layer. The
remaining, better-supported explanation for GB being dropped ~40-90 ms in while
GMS is sustained on the identical channel/node id:

- **Single-owner connection.** The watch appears to allow only one Data Layer
  RFCOMM session, and `com.google.android.gms` (Wear_Transport) auto-connects and
  owns it. GB and GMS were observed dropping in lockstep — the watch tears down
  the duplicate. GMS reclaims within ~1 s, so GB never keeps it.

`node.db.nodeinfo` also stores a per-node `enrollmentId` (UUID) and seqId
(watch 1cf38db4 → d95cdffb-..., phone e1b1cd8b → 6e4488f9-...); the peer-sync
counters GB should ideally present are those seqIds, not 0. Not proven to be the
drop cause, but relevant for a faithful client.

### Revised next step (was dismissed prematurely)
Durably stop GMS Wear from connecting to this watch (disable the GMS wearable
node connection for it, or set its connectionConfigurations row disabled), THEN
test GB alone on the channel. If GB then sustains, the blocker was ownership/
collision, not protocol — and a standalone GB companion becomes viable (with GMS
Wear disabled for the watch). This is the clean experiment §17 wrongly skipped.

---

## 19. Option 2 tested — not collision either; drop cause still open (2026-09-18)

Durably removed GMS Wear from the watch connection and tested GB alone:
- Disabled both Samsung apps (`pm disable-user`), force-stopped GMS, and set the
  watch's `connectionConfigurations.connectionEnabled=0`. Confirmed **no
  `Wear_Transport` activity** in logcat during the test — GMS was genuinely out.
- GB still dropped ~90 ms after the watch's NodeInfo reply, every cycle. The watch
  sends its NodeInfo (112 B) but never its peer-sync, then tears down.

Also tried, all with no change:
- Impersonated node id `e1b1cd8b` vs GB's own fresh random id.
- peer-sync field8.field1 = -1 (0xffff…ff) vs 0.
- Eager (bundled) vs reply-driven peer-sync.
- Correct live watch node id (`1cf38db4`); correct RFCOMM channel (same as GMS).

So the drop is **not crypto (§18), not connection-collision, and not any app-level
field we can currently vary.** The watch exchanges NodeInfo with GB and then
declines to proceed.

### Leading remaining hypotheses (untested)
1. **Concurrent BLE companion presence.** GMS keeps a BLE/GATT connection to the
   watch alongside RFCOMM; the watch may drop an RFCOMM Data Layer peer that has no
   accompanying BLE companion session. (This would re-implicate the GATT service
   `3675c0ff…`/`c8625a0b…` from §8 — as a presence/keepalive channel, not crypto.)
2. **RFCOMM socket security/mode** GB's `BluetoothSocket` negotiates differently
   from GMS's (secure vs insecure, credit-based flow control, MTU/PN parameters).
3. A NodeInfo/peer-sync detail still not matched byte-for-byte (e.g. per-node
   seqId counters from `node.db.nodeinfo` instead of 0; field 11 SDK value).

### Bounded next experiments for a future session
- btsnoop byte-diff GB's exact NodeInfo+peer-sync vs GMS's opening on this watch,
  incl. RFCOMM PN/credit/MTU parameters (the mux-level negotiation).
- Hold a BLE GATT connection to the watch (companion presence) while GB does the
  RFCOMM handshake, and see if the watch then proceeds.
- Present real per-node seqId counters in peer-sync.

Phone left restored: Samsung apps re-enabled, GMS connection config re-enabled,
Gadgetbridge stopped.

---

## 20. Reconciled model — connection setup is GATT-auth-gated; data is plaintext (2026-09-18)

Deeper GMS teardown reconciles §17 and §18. Both were partially right:

- GMS's Wear connection setup is a **state machine** (obfuscated enum `ioom`,
  action codes): `ESTABLISH_CONNECTION`, `BEFORE_CREATE_BOND_BONDING`,
  `SECRET_HANDSHAKE`, `SECRET_HANDSHAKE_GATT_COMMUNICATION`,
  `GATT_CONNECTION_AND_SECRET_HANDSHAKE`, `CANCEL_BLE_BONDING_BEFORE_HANDSHAKE`,
  `HIGH_QUALITY_MEDIUM_SETUP`, ...
- The secret handshake is executed by `gnte` (Fast Pair Device Config, "FPDC"),
  which builds the handshake via `gntt` (ECDH / 16-byte account key, over a GATT
  characteristic). So connection **setup is authenticated over GATT/BLE**.
- Separately, the RFCOMM **data** transport (Wear_Transport / TransportWriter)
  is **not encrypted** (no cipher in those classes; connectionConfigurations.crypto
  empty). So §18 was right that the data channel is plaintext — but §17 was right
  that a GATT secret handshake gates the *connection*.

Net: to be accepted, a companion must complete the GATT connection + secret
handshake (auth), after which the plaintext RFCOMM Data Layer flows. Gadgetbridge
does only the plaintext RFCOMM half, so the watch exchanges NodeInfo as a courtesy
and then drops it — consistent with every observation.

### Limit of phone-side RE
The phone APKs show what the phone SENDS and how GMS sets up the connection, but
the drop DECISION is made by the watch's own GMS/Wear stack, which is not in these
APKs. To get the authoritative rejection reason (and confirm whether the GATT
secret handshake is strictly required for this specific BT/RFCOMM watch), the next
step is **watch-side logcat**: enable ADB on the Watch7 (Wear OS developer options)
and capture its log during (a) a normal GMS connect and (b) a Gadgetbridge connect.
The watch's log should state directly why it closes the GB connection.

### The secret, concretely
Established at enrollment (Fast Pair ECDH with the device anti-spoofing key, or a
16-byte account key), Google-account-bound, stored in GMS (not in the plainly
named fastpair prefs; likely the account "footprints" / an encrypted store). A
faithful standalone client would have to complete the GATT secret handshake, which
needs that secret — the crux of feasibility.

---

## 21. BREAKTHROUGH — the connection is bidirectional; watch needs back-channels (2026-09-18)

Enabled ADB on the Watch7 (Wear OS) and captured the watch-side log during a
Gadgetbridge connect. This is the authoritative reason for the drop, and it is
neither crypto nor identity:

Watch-side sequence when GB connects the Data Layer:
```
RFCOMM_CreateConnectionWithSecurity scn=9 is_server=true uuid=5e8945b0…  (watch accepts GB)
BluetoothRemoteDevices: ### RFCOMM : STATE_CONNECTED(uuid 5e8945b0…)
WearBluetooth: Data layer connected. Request proxy RFCOMM connection      ← wants back-channel
WearBluetoothHelper: Request Helper Connection … connect to <phone>
   → Creating BluetoothSocket(uuid: cee50b63-5f79-4727-8b5d-504eb07e1e22)
   → java.io.IOException: bt socket closed, read return: -1               ← back-channel FAILS
CompanionProxyShard: start up new sysproxy connection
   → Creating BluetoothSocket(uuid: fafbdd20-83f0-4389-addf-917ac9dae5b2)  ← WearProxy
BluetoothRemoteDevices: ### RFCOMM : STATE_DISCONNECTED(uuid 5e8945b0…)    ← watch drops Data Layer
```

**The Wear connection is bidirectional.** After the phone opens the Data Layer
(phone→watch, UUID `5e8945b0…`), the WATCH immediately connects BACK to the phone
on two RFCOMM services:
- `cee50b63-5f79-4727-8b5d-504eb07e1e22` — `WearBluetoothHelper` (control channel)
- `fafbdd20-83f0-4389-addf-917ac9dae5b2` — `CompanionProxyShard` / sysproxy (the
  WearProxy TCP-over-BT tunnel; = §13).

Normally GMS on the phone runs RFCOMM **server** sockets (with SDP records) for
these, accepts the watch's back-connections, and keeps them open. Gadgetbridge
does not, so the watch's back-connections fail (`read return: -1`) and the watch
tears down the Data Layer within ~90 ms. This also explains why force-stopping GMS
never helped GB: with GMS gone, nothing answers the back-channels either.

### The fix (correct, not a hack)
Gadgetbridge must act as the full companion:
1. Keep the phone→watch Data Layer client (5e8945b0) — already done.
2. Register SDP + **listen** as an RFCOMM server on the phone for
   `cee50b63-…` (Helper) and `fafbdd20-…` (WearProxy/sysproxy), accept the watch's
   connections and keep them open.
   - Minimum viable test: accept and hold both sockets open (don't close) and see
     if the watch keeps the Data Layer up.
   - Full: implement the Helper control protocol and the sysproxy tunnel (TCP-over-
     BT for the watch's internet access) as needed.

This was found via watch-side logcat (Wear OS ADB) — the phone APKs alone could
not show it, because the drop is the watch reacting to a failed back-connection.

---

## 22. SOLVED — sustained Data Layer connection achieved (2026-09-18)

Gadgetbridge now holds a stable Data Layer session with the Galaxy Watch7: the
watch streams its full DataItem set (settings, complications, ...) to GB and the
connection stays up for minutes with no teardown. Watch-side log shows no
`STATE_DISCONNECTED`, no `Mismatched node`, no digest error.

The watch-side logcat (Wear OS ADB) was decisive: each fix exposed the next gate,
and all are now resolved. The three things that mattered, in order of discovery:

1. **Message-integrity digest (the core bug).** Envelope field 2 is NOT a random
   request id — it is `base64url(SHA-1(payload))` without padding (GMS `gjys.r`,
   verified byte-for-byte against the reference capture). GB was filling it with a
   random value, so the watch's transport aborted with
   `Unsegmented message digest should match the received digest`. Fixed in
   `WearDataLayerProtocol.payloadDigest()` / `wrapEnvelope()`.
2. **Node id must equal the enrolled companion node.** After the digest fix, the
   watch complained `Mismatched node is attempting to connect: clientNodeId is
   e1b1cd8b and message nodeId is <random>`. The watch records the enrolled
   companion's node id (`e1b1cd8b`, the phone's GMS Wear node id) and rejects any
   other. GB must present it (currently `EXPERIMENT_IMPERSONATE_NODE_ID`).
3. **Bidirectional back-channels (§21).** GB must run RFCOMM servers for the
   watch's return connections: `fafbdd20-…` (WearProxy/sysproxy) and
   `cee50b63-…` (WearBluetoothHelper), accept them and keep them open.

Plus the earlier-established pieces: NodeInfo with field 3 (nonce) + field 7 (the
shared connection long-id), peer-sync field8.field1 = 0xffffffffffffffff for a
fresh session, correct live watch node id, correct RFCOMM channel.

Note this reconciles §17/§18/§20: there IS a Fast Pair GATT secret handshake in
GMS, but it is NOT required for this bonded BT/RFCOMM watch — the actual gates were
the SHA-1 message digest, the node-id match, and the back-channels. No crypto
secret was needed.

### Remaining work (connection works; now build features on it)
- Learn the enrolled node id dynamically instead of hardcoding `e1b1cd8b`
  (readable from GMS `node.db`/`connectionconfig.db`, or captured once).
- Implement the back-channel protocols properly (sysproxy tunnel + Helper control)
  rather than just holding the sockets open, as far as features require.
- Keep GMS Wear from re-claiming the watch while GB owns it (single-owner channel).
- Parse the watch's incoming DataItems and wire up settings/notifications.

---

## 23. Incoming DataItem stream decoded; watch settings model (2026-09-18)

With the connection sustained, Gadgetbridge now reassembles the incoming stream
(4-byte length prefix) and decodes each envelope. The watch streams its full state
on connect; observed DataItem paths (all sustained, no drop):

- Settings (`wear://<node>/protogensettings/<group>/<key>/<node>`):
  `display/always_on_display`, `display/tilt_to_wake`, `display/touch_to_wake`,
  `display/screen_off_timeout_ms`, `display/font_scale`,
  `modes/audio_volume_muted`, `modes/use_mode_ringer`, `modes/mode_ringer`,
  `unread_dot/show_unread_dot`, `wrist_orientation/wrist_orientation_mode`,
  `location/network_location_opt_in`, `location/combined_location`,
  `accessibility/talkback_enabled`, `accessibility/wear_accessibility_gesture_enabled`,
  `accessibility/magnification`, `media_controls/auto_launch`,
  `wear_launcher_ui_mode`.
- Notifications: `notification_settings/smart_replies_enabled`,
  `notification_settings/notification_cooldown_enabled`,
  `bridge_mode/bridge_mode/<app package>` (calendar, messaging, reminder, …).
- Also: `settings/home_info`, `trusted_packages`, `wcs_extension/internal/status`,
  `installed_apps/…`, `complication_providers/…`, `capabilities/…`, `cloud/peers`.

The real always_on_display path is
`…/protogensettings/display/always_on_display/<node>` (source
`com.samsung.wearable.watch7plugin`) — GB's placeholder
`/w_module_sec_watch_setting/display/always_on_display` needs updating to the
`protogensettings` scheme before settings writes will take effect.

Implemented: incoming stream reassembler + `WearDataLayerProtocol.describeMessage()`
(NodeInfo / peer-sync / DataItem-with-path / MessageEvent). Larger DataItems arrive
as multi-KB segmented transport messages (seen as `payload(other, …B)`); segment
reassembly (GMS `gkds` queueId/piece/digest) is still to be implemented for those.

---

## 24. Settings write works end-to-end (2026-09-18)

Gadgetbridge changed a real watch setting over its own connection, no Samsung app.

Wire format of a `protogensettings` boolean write (confirmed by round-trip):
- DataItem at `wear://<ownNode>/protogensettings/<group>/<key>/<ownNode>`, e.g.
  `.../protogensettings/display/always_on_display/<ownNode>`.
- field 1 = source package (`com.samsung.wearable.watch7plugin`).
- field 2 = path.
- field 4 = value proto `{ field1 = { field2 = 0|1 }, field2 = <write time ms> }`
  (bool uses inner field 2; int uses inner field 1; float uses inner field 5).
- field 5 = seq, field 6 = 0 (not a delete), field 7 = ownNode.
- field 9 = the source app's signing-cert SHA digest
  (`9ca5170f381919dfe0446fcdab18b19a143b3163`) — the watch checks source package +
  signature against its trusted_packages.
- field 10 = write time ms. Envelope field 2 digest as always (§22).

Test: authored `always_on_display=ON` as our connection node e1b1cd8b. The
connection survived (no drop) and the watch applied it and echoed it back on its
own node: `wear://1cf38db4/protogensettings/display/always_on_display/1cf38db4`
value flipped 0→1, and the legacy `w_module_sec_watch_setting/display/
always_on_display` DataMap showed `booleanIsChecked=true`. Timestamp is the
logical clock — a write with a higher ms wins, so a fresh System.currentTimeMillis()
always takes effect.

Implemented `WearDataLayerProtocol.buildProtogenBooleanSettingPut()`, wired
`onSendConfiguration(always_on_display)` to it, plus a temporary one-shot test
(`EXPERIMENT_TEST_SETTINGS_WRITE`) that pushes ON once per connect.

### Next
- Wire the remaining protogensettings (tilt_to_wake, touch_to_wake, unread_dot,
  modes/*, etc.) and int/float types to the GB settings UI; drop the test hook.
- Read current values (decode the protogen value proto) to reflect state in the UI.
- Notifications: the notification_settings + bridge_mode paths are the levers.

---

## 25. Settings feature: 5 booleans wired both ways; int/float writers (2026-09-18)

- Generic protogensettings writers: `buildProtogenBooleanSettingPut` (inner field 2),
  `buildProtogenIntSettingPut` (inner field 1), `buildProtogenFloatSettingPut`
  (inner field 5, fixed32). Int/float encodings byte-verified against captured
  values (screen_off_timeout_ms=60100 → `08 c4d503`; font_scale=1.0f → `2d 0000803f`).
- Wired 5 confirmed boolean settings to the GB UI (SwitchPreferenceCompat + strings):
  always_on_display, tilt_to_wake, touch_to_wake, unread_dot/show_unread_dot,
  modes/use_mode_ringer. `onSendConfiguration` maps the pref key → group/key and
  writes via the protogen PUT.
- Incoming reflection: `reflectIncomingSetting` parses the watch's own-node
  protogensettings DataItems and mirrors the boolean into the device pref, so the
  UI shows the real current state. Verified live: tilt_to_wake/touch_to_wake/
  show_unread_dot/always_on_display all reflected = true; connection stayed up.

### Notifications - what's needed next
Notification *settings* paths are known (notification_settings/smart_replies_enabled
[int-style inner field 1], notification_cooldown_enabled [bool inner field 2],
bridge_mode/<app>). Notification *delivery* (showing a phone notification on the
watch) is a separate message whose format is not yet captured - needs a capture of
watch7plugin posting a real notification (watch7plugin temporarily re-enabled +
a phone notification), which requires user setup. Not guessed here.

---

## 26. Settings WRITE reaches the watch but is not applied (2026-09-18)

Fixed the settings-dispatch bug (§ the switches never called onSendConfiguration —
added GalaxyWatch7SettingsCustomizer with addPreferenceHandlerFor). After that,
toggles do send writes. But the watch does not APPLY them. Investigated with
watch-side logcat (Wear OS ADB, now stable):

- GB writes e.g. `wear://efb3c185/protogensettings/display/touch_to_wake/efb3c185`
  = false (correct scheme + node + structure, matching stock client [11]).
- Watch side: `WearServices [DispatchingGmsEventListener] onDataChanged:
  type = CHANGED, uri = wear://efb3c185/protogensettings/display/touch_to_wake/efb3c185`
  — so the watch's Data Layer ACCEPTS the write and fires the change event. It is
  NOT a sync/seq/stale rejection (seq 757+, above current; timestamp higher than
  the watch's own value; all ruled out).
- But NO component logs applying it, and the setting/behaviour does not change.
  The real client's identical write applies; GB's does not.

So the block is in the watch's protogensettings APPLIER, not the transport. Also
noted: Samsung's own settings mirror uses `w_module_sec_watch_setting/*` handled by
`StDataLayerListener` (pid = com.google.android.apps.wearable.settings), which
explicitly skips `from : watch` and processes phone-originated changes — a separate
path from the Google `protogensettings/*` applier.

Ruled out for the non-application: DataItem seq (raised above the node's current
via tracking the incoming stream), logical-clock timestamp (ours > watch's),
authoring node (tried both e1b1cd8b connection node and efb3c185 settings node),
and message structure (byte-shape matches the reference).

### Open — needs deeper watch RE
Find the watch component that consumes `protogensettings/display/*` and applies it
to the system, and why it ignores GB's (vs the stock client's) otherwise-identical
write — likely a source/authority check not visible at default log level. This is
the remaining nut for write control; read + connection are solid.

### Connection stability note
GB ran as the sole companion (GMS Wear disabled for the watch) for ~20+ minutes
across these tests with zero drops — the earlier watch destabilisation was from
GB and GMS both active, not from GB alone. A clean handover (GMS Wear off, then GB)
is stable; flip-flopping GMS on/off while GB is active is what breaks it.

---

## 27. Fuller peer-sync did NOT fix write application (2026-09-18)

Implemented the ownership hypothesis from §26: track every node's counter from the
incoming stream and, right before a settings write, send a fuller peer-sync that
declares all observed nodes with their counters (like the stock client's 9-node
peer-sync), asserting ownership of the settings node efb3c185.

Result: no change. The watch still does not apply the write (touch_to_wake toggle
had no effect). So the block is not the peer-sync node declaration.

Everything phone-side that can be varied has now been ruled out: authoring node,
DataItem seq, logical-clock timestamp, message structure/signature, and peer-sync
node ownership. The write reaches the watch (§26 onDataChanged) but its
protogensettings applier ignores it for a reason only visible inside that watch
component.

### The remaining path
Decompile the watch's settings applier (com.google.android.apps.wearable.settings /
the protogensettings consumer) to read its validation and see why it skips our
otherwise-identical write. This needs a stable watch ADB to pull the APK - the
watch's wireless ADB has been too flaky (drops within seconds) to reliably grab it.
A rock-solid channel (ADB over USB / a dock, or keeping the watch awake on charger
in the same network) would let this be finished.

### Deliverable state
Solid and durable: GB establishes and holds the Data Layer connection as the sole
companion without the Samsung app, decodes the full incoming DataItem stream
(settings, apps, battery, ...), and delivers correctly-framed writes that reach the
watch. Only the watch-side application of setting writes is unresolved.

---

## 28. SETTINGS WRITE SOLVED — w_module_sec_watch_setting via StDataLayerListenerService (2026-09-18)

Decompiled the watch's settings applier (pulled SecClockworkSettings.apk +
ClockworkWcsExt.apk over a stable watch ADB) and read
`StDataLayerListenerService.onDataChanged`. It is the component that APPLIES
settings, and it processes the **Samsung `w_module_sec_watch_setting/*`** scheme —
NOT `protogensettings/*` (that was the wrong scheme; protogensettings reached the
watch's Data Layer but nothing applied it). Requirements decoded from the service
+ `StDataLayerDisplayManager`:

1. Path `wear://<node>/w_module_sec_watch_setting/<sub>` where `<node>` is a
   CONNECTED peer node — i.e. our connection node `e1b1cd8b` (the service's
   `isValidNodeId` checks the DataItem host against the connected peer / peer list).
   Writing under efb3c185 or any non-connected node fails this check.
2. DataMap `{ "booleanIsChecked": <bool>, "from": "mobile" }`. The service SKIPS any
   item whose `from` is null or `"watch"` (`StNewPhoneConstants.Sender.WATCH`); a
   phone write must use `"mobile"` (`Sender.MOBILE`).
3. Wear DataMap TypedValue wire format: entry = {f1=key, f2=typedValue};
   typedValue = {f1=typeTag, f2=container}; bool → typeTag 8, container {f8=0|1};
   string → typeTag 2, container {f2=string}.

Confirmed sub-paths (StDataLayerDisplayManager):
- always_on_display: `display/always_on_display`
- touch to wake:     `display/wake_on_touch_screen`
- tilt to wake:      `display/wake_on_wrist_raised`
- turning bezel:     `display/wake_on_turning_bezel`
- others: show_charging_info, bold_font, touch_sensitivity, ...

Verified live: GB wrote `wear://e1b1cd8b/w_module_sec_watch_setting/display/
wake_on_touch_screen = false` and the watch applied it —
`StDataLayerListener: isValidNodeId:true ... from : mobile ... handleSyncData :
really ...` and the behaviour changed on the watch. Implemented as
`WearDataLayerProtocol.buildWatchSettingBooleanPut` + wired in onSendConfiguration
(node = connection node, seq kept above the global max from the incoming stream).

Read + connection + settings write now all work as a standalone companion (Samsung
app disabled). protogensettings write path is retained in code but unused.

---

## 29. Full settings catalog wired (2026-09-18)

Decompiled all StDataLayer*Manager classes in SecClockworkSettings and cataloged
every `w_module_sec_watch_setting/*` setting. Wired all 29 boolean (`booleanIsChecked`)
toggles into the GB device settings UI (grouped: Display, Gestures & buttons,
General, Bedtime, System sounds & vibration), each written via
buildWatchSettingBooleanPut under the connection node with from:"mobile".

Boolean toggles wired (sub-path under /w_module_sec_watch_setting/):
- display/always_on_display, display/wake_on_touch_screen, display/wake_on_wrist_raised,
  display/wake_on_turning_bezel, display/auto_low_brightness, display/bold_font,
  display/show_charging_info, display/touch_sensitivity
- advanced/gestures/quick_action, .../quick_action/watchface/second_action_enabled,
  advanced/gestures/dismiss_alerts_and_reject_calls, advanced/gestures/raise_to_talk/enabled,
  advanced/action_button/siren/enabled, advanced/labs/quicklaunch/enabled,
  advanced/media_control/auto_launch_media_controls, advanced/media_control/show_ongoing_icon
- general/touch_bezel, general/touch_bezel_feedback, bedtime/turn_on_scheduled
- sound_settings/system_sounds/{charging_sound,dialing_keypad_tone,hourly_chime,
  samsung_keyboard,ticking_sound,touch_sound,unlock_sound}
- sound_settings/system_vibration/{charging,dialing_keypad,samsung_keyboard}

Special / not yet wired (need extra handling):
- securitysetting/wrist_detection uses key `booleanIsDisabled` (INVERTED) - not wired.
- String settings (key + selection list needed): display/font_style/selected_item
  (fontNameString), sound_settings/sound_mode (stringMode),
  sound_settings/{ringtone,ringtone_vibration,notification_sound,notification_vibration}/
  selected_name (selectedNameString), advanced/action_button/*, advanced/double_press/
  selected_package (stringPackageId), advanced/press_and_hold/selected_name,
  advanced/recents_key/selected_name, advanced/status_indicator/selected_item,
  advanced/gestures/quick_action/*/setting, advanced/labs/quicklaunch/launch_package.
- Numeric (e.g. display/brightness key `intLevel`, screen timeout, font size, volume,
  vibration intensity) - separate managers, need slider UI + value mapping.

These use the same proven write path; wiring the string/numeric ones needs UI
widgets (list/slider) and value enumeration, left as follow-up.

---

## 30. Battery level reporting (2026-09-18)

The watch streams its battery in a DataItem `wear://<watchNode>/batterystate/data/
batterystate`. Decoded value proto: field 1 = level 0-100 (verified against the
watch's `dumpsys battery` level=100), field 5 = AOSP BatteryManager status
(2=charging, 3=discharging, 5=full); field 2 is the plugged type. (There is also a
richer `c_devicecare/battery/change` DataMap with a `battery_level` key, not needed.)

Implemented `WearDataLayerProtocol.parseBatteryState` (returns {level, status}) and
`GalaxyWatch7DeviceSupport.reflectBattery`, which posts a GBDeviceEventBatteryInfo
(level + BatteryState mapped from status) on each change. The coordinator already
reports one battery (default getBatteryCount=1), so it shows in the GB UI. Builds
clean; live-verified against dumpsys (level 100 / full) - end-to-end UI check
pending the phone's USB ADB coming back.

---

## 31. Fix battery display + reflect settings state into UI (2026-09-18/19)

Two user-reported issues fixed:
- Battery was not shown because the battery build had never actually installed (the
  earlier install hung when the phone's USB ADB dropped). Reinstalled; battery now
  reports (`battery 100% state BATTERY_CHARGING_FULL`), field 1 = level confirmed
  again (0x61 = 97 off charger).
- Settings switches all showed OFF and didn't reflect the watch. reflectIncomingSetting
  was still written for the old protogensettings scheme; rewrote it for the actual
  w_module_sec_watch_setting scheme: parse the incoming DataItem's sub-path and its
  DataMap `booleanIsChecked` value (added WearDataLayerProtocol.parseWatchSettingSubPath,
  parseDataMapBoolean + ProtoReader.findAllMessages for repeated DataMap entries) and
  mirror it into the device pref. Verified live: many settings reflected true from the
  watch (touch_bezel, always_on_display, wake_on_*, system sounds/vibration, ...), so
  the switches now show the real current state.

---

## 32. Numeric + string settings wired (2026-09-19)

Decoded the DataMap TypedValue wire format for the remaining value types by capturing
the watch's own full settings sync (reconnect triggers peer-sync field1=-1 "send all").
Each DataMap entry is {f1=key, f2=typedValue}; typedValue={f1=typeTag, f2=container}
where the container's inner field number equals the typeTag:
- bool  -> typeTag 8, container {f8 = 0|1}   (already known)
- string-> typeTag 2, container {f2 = string}
- int   -> typeTag 6, container {f6 = varint}
- long  -> typeTag 5, container {f5 = varint}  (used by the "time" entry)
- array -> typeTag 10, container repeated {f10 = nested typedValue}  (read-only lists)

Added buildWatchSettingIntPut / buildWatchSettingStringPut (+ dataMapInt) and
parseDataMapInt / parseDataMapString to WearDataLayerProtocol. The value key differs
per setting: brightness/font_size/volumes use "intLevel"; vibration_intensity and the
*/selected_item list settings use "intSelectedItem"; sound_mode uses "stringMode".

Wired (verified against each manager's handleSyncData actually applying the value):
- Sliders (SeekBarPreference, int "intLevel"): display/brightness (0-254, max from
  brightness_max_level=254), display/font_size (0-5, max from font_size_max=5),
  sound_settings/volume/{media,notifications,ringtone,system} (0-15).
- List (string "stringMode"): sound_settings/sound_mode = mute|vibrate|sound.
- List (int index "intSelectedItem"): sound_settings/vibration_intensity = 0 Light / 1 Strong.

Live-verified reflection end-to-end after reconnect: font_size=2, sound_mode="vibrate",
volume/media=7, vibration_intensity=1, brightness=48 - all decoded values matched the
raw capture and populated the UI (the sound_mode dialog pre-selected the real value).
The sound_mode write fired correctly (`sending w_module sound_settings/sound_mode =
"sound" under node e1b1cd8b`); direct watch-side confirmation was pending the watch's
wireless ADB (dropped at the time). Write format is the watch's own serialization on
the proven boolean transport path.

Deliberately NOT wired (watch-side handleSyncData is empty -> writes are ignored):
- display/font_style/selected_item (echo-only)
- securitysetting/wrist_detection (echo-only; also inverted key booleanIsDisabled)

Still follow-up (need dynamic list population from the watch's */list DataItems, which
GB's static preference XML can't express well): ringtone/notification selected_name,
screen_time_out/selected_item, and the package pickers (double_press, press_and_hold,
recents_key, quicklaunch launch_package, action_button, digital_assistant, status_indicator).

---

## 33. Settings WRITE fixed for good: per-node data-version seqId (2026-09-19)

The settings-write/apply path had regressed (writes reached the watch byte-correct,
node fully registered - `setConnectedNodeId(e1b1cd8b)`, `isValidNodeId` capable - yet
nothing applied and the watch's GMS logged no dataChanged). Root cause: the DataItem
seq (Wear per-node data version) was seeded from the WATCH's own node counter
(`settingsSeq`, ~35133), but the watch tracks OUR node's version separately and
ignores any DataItem we host whose seq is not greater than the counter it already
holds for our node. The watch reports that counter in its peer-sync, per node:

  peer-sync node-ref: {f1 = nodeId, f2 = counter}
  ...12 0e 0a08 6531623163643862 10 879a02  -> {e1b1cd8b, 36103}

So our writes at seq 1 and 35134 were both <= 36103 and silently dropped. ("Worked
before" was timing luck: right after a connect/enrollment the watch's counter for our
node was low enough that settingsSeq exceeded it.)

Fix: added `parsePeerSyncNodeCounter(message, nodeId)` and `seedOwnDataVersion` - a
dedicated `ownDataVersion` counter seeded to the watch's advertised counter for our
node and incremented per write (36104, 36105, ...). Verified live end-to-end, watch
actually applying each type:
- bool  system_vibration/dialing_keypad: `settings get system dialing_keypad_vibrate`
  1 -> 0; watch logged isValidNodeId:true, onDataChanged from:mobile, handleSyncData,
  StDataLayerSystemVibrationManager enable:false.
- string sound_mode = "sound": watch ringer mode VIBRATE -> NORMAL; SoundModeManager
  sound mode: 2.
- int    vibration_intensity = 0 (Leicht): `setting_vibration_intensity` 3 -> 1;
  StDataLayerVibrationIntensityManager setVibrationIntensity:1.

Settings write (all types) + reflection + battery are now confirmed working with GB as
the sole companion.

## 34. NOTIFICATION PUSH — WORKING end-to-end (GB as sole companion)

Implemented phone->watch notification forwarding using the Wear notification-stream
DataItem convention captured in §5f/§5g. Confirmed live on the real watch (no Galaxy
Wearable / watch7plugin): two GB test notifications rendered in the watch's own
notification panel ("Title" / "Notification body", correct time + Gadgetbridge-blue
accent).

Path (DataItem hosted under OUR node, same transport/seqId rule as settings §33):

  wear://<ownNode>/bridger/stream_item:<ownNode>:<packageName>:<notifId>:<tag>

- `<ownNode>` = our connection node (e1b1cd8b). The stream key repeats it as the
  originating node; `<tag>` is "null_tag" when the notification has no tag.
- PUT = DataItem with delete flag (field 6) = 0 and the DataMap payload; DELETE
  (dismissal) = same path, field 6 = 1, no payload.
- seq MUST exceed the watch's per-node counter for our node -> reuse the same
  `ownDataVersion` counter as settings (one per-node data version covers all our
  DataItems, settings and notifications alike; the watch bumped it 36106 -> 36107 ->
  36108 as it accepted each write, proving persistence).

DataMap fields we send (generic §3 DataMap encoding; the two field-type gotchas below
were found by decoding the watch's own `[DataMap]` cast warnings live):
  app_name (string), title_raw/title_html (string), text_raw/text_html (string),
  sub_text_raw/sub_text_html (string), post_time_millis (int64), when (int64),
  show_when (bool), color (INT32 ARGB), notification_flags (int32),
  notification_style=1, defaults=2, group_alert_behavior=0, has_content_intent=false,
  is_emergency=false, is_group_summary=false, is_work_profile=false,
  only_alert_once=false, user_id=0.

Field-type corrections vs the §5g capture guess (watch's DataMap reader is strict):
- `color` is read as **Integer (int32)**, NOT int64 — sending a Long makes the reader
  log "Key color expected Integer but value was a java.lang.Long" and default to 0.
  Encoded as int32 ARGB, it came through as -14575885 == 0xFF2196F3 (GB blue).
- `supplemental_data` / `supplemental_assets` are **Asset-typed** (icon/picture blobs),
  not ints — sending an int errors ("expected Asset but value was a java.lang.Integer")
  and they are simply omitted (we attach no Wear Assets, so no icon/large-icon/app-icon;
  the watch renders text-only, which is fine).

Watch-side flow confirmed (WearServices / SysUI logcat): `onDataChanged` for our
stream_item uri -> `WatchBridgedItemsController.buildBridgedNotificationForDataItem`
(clean, no cast warnings after the fixes) -> `Posting new remote notification`
(appName=Gadgetbridge, isOngoing=false, hasGroupSummary=false, color=0xFF2196F3) ->
`StreamTimeline STREAM_POST` -> SysUI `WNotiVspPanel addElement`. It only skipped the
buzz/heads-up with "Not alerting: Device is off-body" because the watch was on the
charger; on-wrist it would alert.

Assets (icons, big-picture, action buttons) still TODO: the stock app attaches them as
separate binary Wear Assets referenced by digest (§5g, numAssets=3) — a later step.
Dismissal DELETE is implemented (`buildNotificationDelete`, delete flag=1) and is a
trivial variant of the proven PUT transport, but a phone->watch dismissal round-trip
was not separately live-tested this session.

### GB-side wiring
- `WearDataLayerProtocol.buildNotificationPut/buildNotificationDelete` +
  `buildStreamItemDataItem` + `dataMapLong` helper.
- `GalaxyWatch7DeviceSupport.onNotification` (maps NotificationSpec ->
  title/body/sourceName/sourceAppId/flags/when, seq from `ownDataVersion`) and
  `onDeleteNotification` (rebuilds the path from a `sentNotifications` id->{pkg,tag}
  map). Reuses the settings source package + signature.

### Test harness note (for future sessions)
The `com.xinesoft.notifications` tester marks EVERY notification `FLAG_GROUP_SUMMARY`
(flags=536), which GB drops ("no wearable action present"), so it can't exercise the
push path. Use GB's own Debug screen -> Benachrichtigungen -> **Send NotificationSpec**
(calls DeviceService.onNotification directly, bypassing the NotificationListener
filters). For the real NotificationListener path, note GB defaults filter notifications
when the phone screen is on (`notifications_generic_whenscreenon`) and low-priority ones
(`notifications_ignore_low_priority`).

## 35. Navigation display — INVESTIGATION (CoMaps/turn-by-turn on the watch)

Question: can GB push turn-by-turn navigation (GB already ingests CoMaps via
CoMapsNavigationReceiver -> NavigationInfoSpec -> onSetNavigationInfo) to the
Galaxy Watch7? Investigated live (no implementation yet).

Finding: **navigation on this watch is exclusively Google Maps' domain.** The only
navigation-related channels the watch advertises are Google Maps Wear-app
capabilities, all under `com.google.android.apps.maps` (+ Google's signing cert
`38918a453d07199354f8b19af05ec6562ced5788`):
  - `.../wearable_navigation`
  - `.../improved_tethered_nav_communication`   (tethered = phone navigates, watch mirrors)
  - `.../send_eta_changes`
com.google.android.apps.maps IS installed on the watch (v25.14, but currently
stopped). There is NO generic system "navigation" DataItem/MessageApi path a
companion can write (the only other "tethered" capability is Samsung's
`wcs_tethered_two_way_sync`, which is settings/data sync, not nav). Google Maps is
NOT installed on the phone.

Consequence: GB cannot make the watch render a native nav card just by emitting a
NavigationInfoSpec-shaped DataItem. Two possible routes:

1. **Impersonate phone-side Google Maps** and speak Maps' tethered-nav protocol to
   the watch's Maps Wear app (author DataItems/MessageEvents under
   com.google.android.apps.maps + Google's cert, same authoring trick as settings).
   Native nav UI, but: the message format is a closed, versioned Google protocol we
   don't have — needs a captured real Maps nav session (hard right now: GB occupies
   the connection, so it'd require temporarily restoring stock GMS Maps nav while
   sniffing) or decompiling Maps. It would also display CoMaps data inside Google
   Maps' UI. High effort; questionable value.

2. **Notification-card approach (pragmatic):** reuse the working
   `/bridger/stream_item` notification transport (§34) to push a single, self-updating
   card ("Links auf Hauptstraße · 200 m"), refreshed each turn by re-PUTting the same
   stream id. Low effort (transport proven). Caveats: it's a plain notification, not a
   nav UI; must avoid the ongoing-notification filter (the bridge drops FLAG_ONGOING
   items - §5f) and re-alerting on every turn (use only_alert_once + same id).

Recommendation: native Maps-style nav is a large RE project for little payoff on
CoMaps data; the notification-card route is the realistic way to get turn-by-turn
onto the GW7 with what we already have, accepting it won't look like Google's nav.
Not implemented pending a decision.

## 36. Media control — INVESTIGATION (mechanism identified, bigger than expected)

Started implementing media control (GB has onSetMusicInfo/onSetMusicState + music
control device events; watch advertises w_media_control / samsung_media_controls /
third_party_media_controls / adaptive_media_bridging / volume_data).

MessageApi/MessageEvent wire layout decoded (envelope payload field 10):
  {1=seq varint}{2=srcPackage}{3=signingCertDigest}{4=targetNodeId}{5=0}
  {6=path string}{7=data bytes (often JSON)}{8=srcNodeId}{10=4}{13=1}
(added `describeMessageEvent` logging — commit 32bb2ba12). This is reusable for ALL
MessageApi features (media, calls, find, weather).

**Key finding — media on this OS uses `/adaptive_media_bridging/rpc/*`, NOT the legacy
JSON.** Right after connect the watch (com.samsung.android.wcs.extension) proactively
sends `/adaptive_media_bridging/rpc/watch_request` with a nested protobuf RPC payload,
e.g. (field 7):
  12 76 {0a24 "<uuid request id>"  124a {0a02 "OA"  1225 {0801 2801 3a1b ...}}}
i.e. a request-id + method + args RPC envelope. The legacy JSON media msgIds
catalogued in §5b (`mediametadata`/`playbackstate`/`volume`) were NOT seen from this
watch this session — this OS/companion version appears to have moved media to the
adaptive_media_bridging protobuf RPC.

**Why this is a big task (not a quick win):**
- The media protocol is a bidirectional custom protobuf RPC (session list + metadata +
  playback state + transport commands + volume), not a handful of one-shot JSON pushes.
- Both directions are gated on it: the watch's media controller shows nothing (and thus
  sends no transport commands) until the phone answers `watch_request` with the current
  media session via the same RPC — so there is no cheap "commands only" half.
- We cannot easily capture the phone→watch response format now: the side that knows the
  RPC is the stock companion (watch7plugin/wcs.extension), which is no longer the
  companion (GB replaced it). A faithful capture needs a dedicated session with the
  STOCK app as companion + real media playing, sniffing btsnoop + wcs.extension logcat
  both ways (and/or decompiling ClockworkWcsExt.apk, pulled earlier as
  apks_watch/wcs_extension.apk).

Status: mechanism identified + MessageEvent transport decoded/logged; full media RPC
NOT implemented. Recommend a focused stock-app capture session before implementing, or
pivoting to a smaller MessageApi win first (find-my-watch, calls, weather) now that the
MessageEvent layout is understood.

## 37. Incoming calls — display-only alert WORKING (via the notification bridge)

Implemented `onSetCallState` for the Galaxy Watch7. On this watch a phone call rings via
the SAME Wear notification-stream bridge as normal notifications (§34), not (for our
purposes) via Samsung's dedicated w_callservice/w_apps_incallui channel — so we push a
**call-category notification** when the phone rings and delete it when the call ends.

- CALL_INCOMING -> `buildNotificationPut(..., category="call")` under
  packageName `com.android.server.telecom`, appName "Telefon", title=caller
  name/number, body=R.string.incoming_call, a fixed id (0x0CA11) so it stays one card.
- CALL_END / ACCEPT / REJECT / START -> `buildNotificationDelete` for that id.
- Added a `category` field to the notification DataMap (string). The watch reads it and
  maps "call" to its internal CATEGORY_CALL.

Verified live via GB Debug -> Telefonanrufe -> Send CallSpec (stock GB debug tooling that
calls onSetCallState directly, no real call needed): INCOMING showed "Mr. Plow /
Eingehender Anruf" as a call card on the watch (WearServices logged
`packageName=com.android.server.telecom, category=call, category=3 (CATEGORY_CALL),
appName=Telefon, color=0xFF2196F3`), and END removed it (`Dashboard onItemRangeRemoved`).
Only skipped the ring with "Not alerting: Device is off-body" (watch on charger).

**Scope / limits:** this is a display-only incoming-call ALERT (caller + "Incoming
call"), a call-category notification card - NOT the native full-screen ringing UI with
Answer/Decline. Answering/rejecting FROM the watch needs either wearable notification
actions (delivered as binary Wear Assets, like icons - not done) or Samsung's
w_callservice/w_apps_incallui RPC (no capture: the committed stock capture contains only
call *settings*, no live call). A real phone call reaches this same onSetCallState entry
point via GB's telephony receiver, so it works for real calls too, not just the debug
trigger.

## 38. TODO — captures still needed (future stock-app session)

Some features are blocked on capturing the STOCK companion's wire format (the side that
speaks the private protocol is Samsung's app, which GB replaced — so we can't observe
these while GB is the companion). To be done in ONE deliberate session with the stock
Galaxy Wearable / watch7plugin as companion again (btsnoop + com.samsung.android.wcs.
extension logcat, both directions). NOT now — keep the working GB connection meanwhile.

1. **Media control** (§36): real MediaSession playing + watch media controller open +
   press play/pause/next/prev/volume. Need `/adaptive_media_bridging/rpc/*`
   request+response protobuf both ways. (Legacy JSON media path is NOT used by this OS.)
2. **Call answer/decline from the watch** (§37): a real incoming call (the notification-
   simulator app can also generate one), answer/reject on the watch, capture
   `w_callservice` / `w_apps_incallui`. (Display-only incoming-call alert already works.)
3. (Nice-to-have) **Notification icons + action buttons**: captured as separate binary
   Wear **Assets** by digest (§5g) — capture a notification-with-actions to learn the
   Asset framing.

MessageApi/MessageEvent transport layout is already decoded (§36), so once captured,
implementing the sends is straightforward.

**Step-by-step runbook for doing these captures (written to be followed with no prior
context): `docs/GALAXY_WATCH7_CAPTURE_RUNBOOK.md`.**

## 39. Dynamic list settings — WORKING (options pushed by the watch)

Finished the settings feature by wiring the "dynamic" lists whose options are not a fixed
enum but pushed by the watch as a `<base>/list` DataItem. Verified live end-to-end.

Wire format (decoded from the raw bytes): a `<base>/list` DataMap carries key
`arrayItemList` whose value is an ARRAY typedValue `{f1=10, f2={f10=element (repeated)}}`.
Each element is ITSELF a Wear TypedValue, NOT a bare string:
- string option: `{f1=2, f2={f2="<label>"}}` (e.g. notification sounds "Stumm", "Beep", …)
- int option:    `{f1=6, f2={f6=<n>}}`      (e.g. screen-timeout seconds 15/30/60)
`parseDataMapStringArray` decodes each element accordingly (string as-is, number as its
decimal). Some string entries are `"value::label"` (split on "::"). The current selection
is a separate `<base>/selected_item` int (key `intSelectedItem`); the label list and the
selection index arrive as separate DataItems.

GB wiring:
- New `SettingType.LIST_DYNAMIC` (+ optional `listPath` on SettingSpec, since ringtone's
  list is at `sound_settings/ringtone/name/list` while its selection is at
  `sound_settings/ringtone/selected_item`).
- On receive: cache the decoded labels in the device pref `<prefKey>_gw7_entries`
  (newline-joined) and reflect `selected_item` into `<prefKey>` (index as string).
- `GalaxyWatch7SettingsCustomizer.customizeSettings` populates each ListPreference at
  runtime from the cached labels (entryValues = "0".."n-1"); disabled until the watch has
  pushed its options.
- On change: write `<base>/selected_item` with `intSelectedItem` = chosen index.

Wired (all LIST_DYNAMIC): `display/screen_time_out`, `sound_settings/notification_sound`,
`sound_settings/notification_vibration`, `sound_settings/ringtone` (list at
`.../ringtone/name/list`), `sound_settings/ringtone_vibration`.

Verified live: the screen-timeout list populated with 15/30/60 and selecting 60 made the
watch apply it (`StDataLayerScreenTimeout getScreenTimeoutValueByIndex: 60, index: 2` ->
`setScreenTimeout:60000` -> PowerManager `mScreenOffTimeoutSetting: 60000`). The
notification-sound list populated with the real 11 names (Stumm/Beep/Chirp/…). The
ringtone list didn't always arrive this session; the ListPreference stays disabled
gracefully until the watch pushes it.

### §39 addendum — Buttons & gestures pickers

Also wired `advanced/press_and_hold` (long-press Home key) as a LIST_DYNAMIC: options came
through as clean localized labels (Digitaler Assistent / Bixby / Ausschalten-Menü) and a
write applied live (selected_item=1 -> watch resolved to Bixby and echoed selected_name
`com.samsung.android.bixby.agent/...WinkService`; restored to 0 afterwards).

The other advanced pickers are deliberately NOT wired:
- `action_button`, `status_indicator`: their `arrayItemList` entries are raw component
  names / internal tokens the watch does NOT localize inline
  (`com.samsung.android.watch.stopwatch/...StopwatchActionButtonService`, `show`/`hide2`) -
  would need resolving component->app-label (the installed_apps DataItem carries names) and
  a token->string map. Poor UX without that, so omitted.
- `double_press` (selected_package), `digital_assistant` (selected_name),
  `labs/quicklaunch` (launch_package): selection is a STRING, not an int index, written to a
  different path with a not-yet-confirmed DataMap key, and the list is `arrayPackageIdList`
  of raw package ids. Needs a capture of the stock app changing one to confirm the write
  key + label source. Added to the deferred capture list.

## 40. Notification dismissal from the watch — WORKING (watch->phone)

When a bridged notification is dismissed ON the watch, the watch mirrors the DataItem
delete back to the phone: `DataItem path=wear://<ourNode>/bridger/stream_item:<node>:<pkg>
:<id>:<tag>` with no payload (delete). GB now parses that
(`parseNotificationStreamDeleteId` - note the path arrives percent-encoded, `%3A` for ':')
and, if the id is one we still consider active, fires
`GBDeviceEventNotificationControl{event=DISMISS, handle=id}` so the phone notification is
dismissed too. Removing the id first also prevents reacting to the echo of our own delete
(no loop). Verified live: dismissing the card on the watch logged
"notification <id> dismissed on watch, dismissing on phone".

Incidental find while testing: the watch ALSO drives media over the legacy JSON channel
`/w_apps_media_controller/{request_data,mediametadata,playbackstate}` (MessageApi JSON,
the §5b format) - not only the `adaptive_media_bridging` protobuf RPC (§36). It sends
`request_data` for [volume, audio_output, oneui_version] and empty
mediametadata/playbackstate when nothing is playing. This suggests media control MAY be
implementable via the known JSON format without a full capture - worth revisiting.

## 41. Media control — now-playing WORKING via the legacy JSON channel (no capture)

Confirmed the media hunch from §40: the watch drives media over MessageApi JSON on
`/w_apps_media_controller/*` (the §5b format), so no stock-app capture was needed.

Added a MessageEvent SENDER (`WearDataLayerProtocol.buildMessageEvent`, field layout §36)
plus parsers (`parseMessageEventPath`/`parseMessageEventData`). GalaxyWatch7DeviceSupport:
- `onSetMusicInfo` -> `mediametadata` JSON, `onSetMusicState` -> `playbackstate`,
  `onSetPhoneVolume` -> `volume`; envelope `{msgId, msgType:"4", deviceId:<ourNode>, data:{…}}`
  authored under `com.samsung.android.wcs.extension` + its cert, sent to the watch node.
- Answers the watch's `request_data` (volume + last metadata/state).
- `handleMediaMessage` maps incoming transport msgIds to GBDeviceEventMusicControl.

Verified live (GB Debug -> Musik -> Send MusicSpec/MusicStateSpec): the watch's
MediaController received and PARSED our JSON, registered the remote session
(`IMediaControlBackend isPositionIncreased: new: REMOTE:nodomain.freeyourgadget.
gadgetbridge:The Track Name:120000`), sent `media_app{appId:…gadgetbridge}`, and ACKed our
messages (`RemoteMediaControlBackend sendMessage … "playbackstate","msgType":"0"`). GB also
answered the watch's `request_data([volume,audio_output,oneui_version])`. state map: GB
0/1/2 -> Android PlaybackState 3/2/1; duration/position sent in ms.

Still open (follow-ups, not blockers to the data layer which is proven):
- Making the watch's media CONTROLLER UI visibly display it: UmoActivity (the output
  switcher) shows "Keine Wiedergabe"; the phone-media card is a separate surface that may
  need `advanced/media_control/auto_launch_media_controls` on and/or continuous position
  updates to be treated as the live session. Not yet visually confirmed on screen.
- Reverse transport commands (watch button -> phone): scaffolding + a msgId->event map are
  in place, but the exact command msgIds are only observable once the controller is bound to
  our session and its buttons are pressed - confirm and extend `mediaCommandToEvent` then.
