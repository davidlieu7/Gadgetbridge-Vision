# Galaxy Watch7 — capture runbook (self-contained)

This is a step-by-step runbook for capturing the **stock Samsung companion's** wire
format for three GW7 features that Gadgetbridge (GB) cannot finish without it. It is
written to be followed WITHOUT prior context on this project. Read
`docs/GALAXY_WATCH7_PROTOCOL_NOTES.md` §3, §5b, §34, §36, §37 for the protocol details
referenced below, but this file alone should be enough to perform the captures.

## Why a capture is needed

GB talks to the Watch7 over a reverse-engineered **Wear OS "Data Layer"** carried on a
**Bluetooth-Classic RFCOMM** channel (SDP UUID `5e8945b0-9525-11e3-a5e2-0800200c9a66`).
Wire framing: a **4-byte big-endian length prefix** then a protobuf "envelope". Two
payload kinds matter here:
- **DataItem** (envelope payload field 9): a `wear://<node>/<path>` key/value item.
- **MessageEvent** (envelope payload field 10): a one-shot RPC-style message. Field
  layout (already decoded): `{1=seq varint}{2=srcPackage}{3=signingCertDigest}
  {4=targetNodeId}{5=0}{6=path string}{7=data bytes, often JSON}{8=srcNodeId}{10=4}{13=1}`.

For the features below the phone-side format is produced by Samsung's companion
(`com.samsung.android.wcs.extension` / `com.samsung.wearable.watch7plugin`). Once GB
becomes the companion, that app no longer runs, so we can only see the phone→watch
format by temporarily putting the STOCK app back as companion and sniffing the link.
The watch→phone direction is already visible in GB's own logcat (GB logs every frame via
`WearDataLayerProtocol.describeMessage` / `describeMessageEvent`), but the phone→watch
direction is what these features must reproduce, so a stock capture is required.

## What to capture (three items — do them in ONE stock-app session)

1. **Media control** — path family `/adaptive_media_bridging/rpc/*` (MessageEvent,
   nested protobuf). Need the request+response BOTH ways: session list, track metadata,
   playback state, transport commands (play/pause/next/prev), and volume. NOTE: this OS
   version does NOT use the legacy JSON `mediametadata`/`playbackstate`/`volume` msgIds
   from §5b — confirm which the stock app actually uses this session.
2. **Call answer/decline from the watch** — paths under `w_callservice` and
   `w_apps_incallui`. Need the incoming-call push (phone→watch) AND the answer/reject
   commands (watch→phone). (A display-only incoming-call alert already works via the
   notification bridge; this capture is only for interacting on the watch.)
3. **Notification icons + action buttons** (nice-to-have) — the stock app sends these as
   separate binary Wear **Assets** referenced by digest (§5g). Capture one real
   notification that has an icon + action buttons to learn the Asset framing.

## Devices & prerequisites

- **Phone**: adb serial `2c211173` (Xiaomi Mi 10T, LineageOS / Android 16, a *userdebug*
  build — `adb root` is available, which makes pulling the btsnoop log trivial).
- **Watch**: adb-over-WiFi id `adb-RFAXA1LCLMP-nEGYZK._adb-tls-connect._tcp` (Galaxy
  Watch7, `SM_L310`; production build, no root, but full `adb shell`). Pair adb once via
  the watch's Developer options → Wireless debugging if the connection dropped.
- Use `adb -s <id> ...` for every command so you address the right device.

### Restoring the STOCK companion (the "reset" step — DESTRUCTIVE, confirm first)

GB currently owns the Data Layer connection. To capture the stock format you must make
the stock **Galaxy Wearable** (`com.samsung.android.app.watchmanager`) + **Watch7 plugin**
(`com.samsung.wearable.watch7plugin`) the active companion again. In practice this needs
a **factory reset of the watch and a fresh pairing through Galaxy Wearable**, because the
watch only trusts one companion enrolment at a time.

⚠️ This wipes the watch. **Get explicit user confirmation before resetting**, and expect
to redo the GB pairing afterwards. This is the one deliberate, hard-to-reverse step — it
is why these captures were deferred. Everything else in this runbook is non-destructive.

Before resetting, note GB's current pairing so you can restore it: GB device address
`BC:A0:80:DB:08:07`, and GB impersonates phone node `e1b1cd8b` (see the device-specific
prefs `galaxywatch7_*`).

## Capture setup (non-destructive)

### 1. Bluetooth HCI snoop (btsnoop) on the phone

Enable the HCI snoop log, reproduce the action, then pull the log.

```
# enable snoop (either Developer options → "Enable Bluetooth HCI snoop log" = Enabled,
# or via settings + a Bluetooth restart):
adb -s 2c211173 shell settings put secure bluetooth_hci_log 1
adb -s 2c211173 shell svc bluetooth disable && adb -s 2c211173 shell svc bluetooth enable
# ... reproduce the action (see per-capture steps) ...
# pull the log (path can vary; these are the usual spots on LineageOS):
adb -s 2c211173 root
adb -s 2c211173 shell 'ls -l /data/misc/bluetooth/logs/ /data/log/bt/ 2>/dev/null'
adb -s 2c211173 pull /data/misc/bluetooth/logs/btsnoop_hci.log ./capture.btsnoop
```
If `adb root` is refused, use a bugreport instead: `adb -s 2c211173 bugreport out.zip`
then extract `FS/data/misc/bluetooth/logs/btsnoop_hci.log` from the zip.

### 2. Phone logcat of the stock companion (both features log richly)

```
adb -s 2c211173 logcat -c
adb -s 2c211173 logcat -v time > phone_stock.log &
# useful tags: WatchSettings_Watch7, Notification4Watch7_NB, NotificationSender,
# DataClientWriter, SettingsDataManager, and anything from process
# com.samsung.android.wcs.extension / com.samsung.wearable.watch7plugin:persistent
```

### 3. Watch logcat (renderer side)

```
adb -s adb-RFAXA1LCLMP-nEGYZK._adb-tls-connect._tcp logcat -c
adb -s adb-RFAXA1LCLMP-nEGYZK._adb-tls-connect._tcp logcat -v time > watch.log &
# useful tags: WearServices, WBridgedItemsController, WNoti, SysUI, and the media/call
# session components com.samsung.android.wearable.media.sessions / *incallui*
```

## Per-capture trigger steps

Verify the phone is unlocked first: `adb -s 2c211173 shell dumpsys trust | grep deviceLocked`
should show `deviceLocked=0` before any UI automation (do NOT bypass the lock screen).

- **Media**: play real audio on the phone (any app with a MediaSession — e.g. open a
  page with the Web MediaSession API in Firefox, or install a music player), then on the
  watch open the media controller and press play/pause/next/prev and change volume. The
  interesting frames are MessageEvents with `{6="/adaptive_media_bridging/rpc/..."}`.
- **Call**: trigger a real incoming call to the phone (a second phone, a VoIP call, or
  the installed "Benachrichtigungs Simulator" app `com.xinesoft.notifications` which has
  an "Eingehender Anruf" template), then answer and (separately) reject ON THE WATCH.
  Interesting frames: `{6=".../w_callservice/..."}` and `w_apps_incallui`.
- **Notification with icon+actions**: post a real notification from a messaging app that
  has action buttons (e.g. an SMS with a reply action). Interesting: the DataItem
  `/bridger/stream_item:...` DataMap plus the ~1KB binary Asset frames around it (§5g).

## Decoding the capture

- The decoded reference from the earlier stock session already lives in
  `docs/captures/watch7plugin_datalayer_messages.txt` (+ the raw
  `watch7plugin_datalayer_session.btsnoop`) — use it as the model for format and layout.
- To decode new frames: extract the RFCOMM (channel used by UUID `5e8945b0…`) payload
  bytes from the btsnoop, split the stream on the 4-byte big-endian length prefix, and
  protobuf-decode each envelope. GB already contains a correct decoder — reuse
  `WearDataLayerProtocol.describeMessage(byte[])` / `describeMessageEvent` (feed it a
  captured frame's bytes) rather than writing a new one.
- For each of the three features, write down: the `path` (MessageEvent field 6 or the
  DataItem `wear://…` path), the source package + signing-cert digest (fields 2/3), and
  the full `data` payload (field 7) decoded field-by-field, for BOTH directions.

## Where to put the results

- Save raw captures under `docs/captures/` (same as the existing files).
- Add a new section to `docs/GALAXY_WATCH7_PROTOCOL_NOTES.md` (next free § number) with
  the decoded formats, following the style of §34/§37.
- Then implementing the sends is straightforward: build a MessageEvent with the decoded
  path + payload using the field layout above and GB's `ProtoWriter`, and route watch→
  phone commands into the matching GB device events (`GBDeviceEventMusicControl` for
  media, `GBDeviceEventCallControl` for answer/reject).

## After capturing — restore GB

Re-pair the watch to GB (the coordinator matches device name `Galaxy Watch7.*`; connect
from GB's device list). Confirm battery + a settings toggle still work to verify the GB
connection is healthy again.
