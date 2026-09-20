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

import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Reverse-engineered wire protocol for the Google/Wear-OS "Data Layer" transport that Samsung's
 * Galaxy Wearable app tunnels over a plain Bluetooth Classic RFCOMM channel (instead of the usual
 * BLE/Wi-Fi transport used by AOSP Wear OS), rather than a Samsung-proprietary protocol.
 * <p/>
 * Found by capturing HCI btsnoop traffic + companion-app logcat while pairing a real Galaxy
 * Watch7 to a non-Samsung Android phone. See the project's research notes for the full writeup;
 * summary of what is implemented here:
 * <ul>
 *     <li>Transport: BT Classic RFCOMM, SDP service UUID {@link #SERVICE_UUID_DATA_LAYER}</li>
 *     <li>Framing: 4-byte big-endian length prefix, followed by a protobuf-encoded message</li>
 *     <li>Envelope: a top-level message whose field 1 wraps exactly one of several payload types,
 *     keyed by field number (7 = NodeInfo handshake, 8 = seq/ack ping, 9 = DataItem put/delete,
 *     10 = MessageApi event), plus a request id (field 2) and a couple of always-1 flags
 *     (fields 3/4)</li>
 * </ul>
 * <p/>
 * This is a first cut based on passive observation, not the official (closed-source) protobuf
 * schema — field numbers/semantics may be incomplete or slightly off in places that weren't
 * empirically exercised. Treat it as a starting point to validate against a real device, not a
 * finished, fully-tested implementation.
 */
public final class WearDataLayerProtocol {
    /**
     * SDP service UUID Samsung's Watch7 registers for the plaintext Data Layer RFCOMM channel.
     * Confirmed present in {@code BluetoothDevice#getUuids()} after bonding, and is the UUID the
     * stock Galaxy Wearable app connects to for settings/notifications/media-control traffic.
     */
    public static final UUID SERVICE_UUID_DATA_LAYER = UUID.fromString("5e8945b0-9525-11e3-a5e2-0800200c9a66");

    // Outer envelope field numbers (field 1 sub-message oneof discriminators)
    private static final int FIELD_ENVELOPE_PAYLOAD = 1;
    private static final int FIELD_ENVELOPE_REQUEST_ID = 2;
    private static final int FIELD_ENVELOPE_FLAG_A = 3;
    private static final int FIELD_ENVELOPE_FLAG_B = 4;
    // Present (= 3) on peer-sync and DataItem envelopes, ABSENT on the NodeInfo handshake
    // envelope - confirmed against the reference capture (see research notes §14).
    private static final int FIELD_ENVELOPE_FLAG_C = 5;

    private static final int PAYLOAD_NODE_INFO = 7;
    private static final int PAYLOAD_SEQ_ACK = 8;
    private static final int PAYLOAD_DATA_ITEM = 9;
    private static final int PAYLOAD_MESSAGE_EVENT = 10;
    // Wear Asset transfer (icons and other binary blobs), research notes §47:
    //   {1 = transfer id, 2 = last-chunk flag, 3 = chunk bytes,
    //    4 = base64url(SHA-1(whole asset)) - final chunk only}
    private static final int PAYLOAD_ASSET = 12;
    private static final int ASSET_TRANSFER_ID = 1;
    private static final int ASSET_LAST_CHUNK = 2;
    private static final int ASSET_BYTES = 3;
    private static final int ASSET_DIGEST = 4;
    /** Chunk size the stock companion uses for asset transfers. */
    private static final int ASSET_CHUNK_SIZE = 12215;

    // Peer-sync (payload field 8) structure, decoded from the reference capture:
    //   field 1 = varint counter (the highest sequence this node has seen from the peer it is
    //             syncing with; 0 for a fresh session with no history)
    //   field 2 = repeated {field1 = nodeId string, field2 = per-peer counter varint}
    //   field 3 = varint flag, always 2
    private static final int SEQACK_COUNTER = 1;
    private static final int SEQACK_NODE_REF = 2;
    private static final int SEQACK_FLAG = 3;
    private static final int NODE_REF_ID = 1;
    private static final int NODE_REF_COUNTER = 2;

    /** The Wear Data Layer "cloud" sync anchor node; present in every real peer-sync list. */
    public static final String CLOUD_NODE_ID = "cloud";

    // NodeInfo fields
    private static final int NODE_ID = 1;
    private static final int NODE_NAME = 2;
    // A large per-node varint (a random ~63-bit connection/instance nonce, distinct per side)
    // present in every real NodeInfo. Its exact value is not validated across sides, but the
    // watch expects the field to be there.
    private static final int NODE_EPOCH = 3;
    private static final int NODE_TYPE = 4;
    private static final int NODE_ROLE = 5;
    private static final int NODE_UNKNOWN6 = 6;
    // A shared connection long-id (a decimal string) that BOTH sides present identically. The
    // watch keeps sending the same value across factory resets, and it drops a peer whose
    // NodeInfo omits (or mismatches) it - this was why the watch replied then immediately
    // DISCed. Learned from the watch's own NodeInfo reply and echoed back on the next connect.
    private static final int NODE_CONNECTION_ID = 7;
    private static final int NODE_SDK_LEVEL = 11;

    // DataItem fields
    private static final int DATAITEM_SOURCE_PACKAGE = 1;
    private static final int DATAITEM_PATH = 2;
    private static final int DATAITEM_PAYLOAD = 4;
    private static final int DATAITEM_SEQ = 5;
    private static final int DATAITEM_DELETE_FLAG = 6;
    private static final int DATAITEM_DEST_NODE_ID = 7;
    // Binds an attached asset to a key the DataMap references by index (research notes §47):
    //   repeated { 1 = key ("0", "1", ...), 2 = { 4 = assetDigest }, 3 = 4 }
    private static final int DATAITEM_ASSETS = 8;
    private static final int ASSET_BINDING_KEY = 1;
    private static final int ASSET_BINDING_REF = 2;
    private static final int ASSET_BINDING_DIGEST = 4;
    private static final int ASSET_BINDING_KIND = 3;
    // The source app's signing-certificate SHA digest (hex) and a write timestamp; the watch
    // checks the source package + signature against its trusted_packages before applying.
    private static final int DATAITEM_SIGNATURE = 9;
    private static final int DATAITEM_TIMESTAMP = 10;

    // protogensettings value proto: field 1 = a typed-value submessage, field 2 = write time (ms).
    // The typed submessage uses inner field 1 (varint) for ints, field 2 (varint 0/1) for bools,
    // field 5 (fixed32) for floats. field 2 (the timestamp) is a logical clock: the watch applies
    // whichever write has the highest timestamp.
    private static final int PROTOGEN_VALUE = 1;
    private static final int PROTOGEN_TIMESTAMP = 2;
    private static final int PROTOGEN_INT = 1;
    private static final int PROTOGEN_BOOL = 2;
    private static final int PROTOGEN_FLOAT = 5;



    private WearDataLayerProtocol() {
    }

    /**
     * Builds the initial handshake message a client sends right after the RFCOMM socket connects,
     * announcing itself as a Wear Data Layer node. Modeled byte-for-byte on the phone-side
     * NodeInfo captured from the stock Galaxy Wearable app.
     * <p/>
     * Crucially includes field 7, the shared connection long-id: the watch drops any peer whose
     * NodeInfo omits (or mismatches) it. It is not known on the very first connect, so pass
     * {@code null} then; parse it from the watch's own NodeInfo reply
     * ({@link #parseWatchConnectionId(byte[])}), cache it, and pass it on the next connect.
     *
     * @param localNodeId   an 8-hex-char id identifying this "phone" node, same shape as the ids
     *                      seen on the wire (e.g. derived from a random/stable per-install value)
     * @param localName     a human-readable name for this node, shown as the connected device name
     *                      in some of the watch's own UI
     * @param nodeEpoch     a large per-node nonce for field 3 (value not validated across sides;
     *                      keep it stable per node so reconnects look consistent)
     * @param connectionId  the shared connection long-id (field 7) learned from the watch, or
     *                      {@code null} if not yet known (first ever connect)
     */
    public static byte[] buildNodeInfoHandshake(final String localNodeId, final String localName,
                                                final long nodeEpoch, final String connectionId) {
        final ProtoWriter nodeInfo = new ProtoWriter();
        nodeInfo.writeString(NODE_ID, localNodeId);
        nodeInfo.writeString(NODE_NAME, localName);
        nodeInfo.writeVarint(NODE_EPOCH, nodeEpoch);
        nodeInfo.writeVarint(NODE_TYPE, 3);
        nodeInfo.writeVarint(NODE_ROLE, 1); // 1 = phone/initiator role, per captured traffic
        nodeInfo.writeVarint(NODE_UNKNOWN6, 0);
        if (connectionId != null) {
            nodeInfo.writeString(NODE_CONNECTION_ID, connectionId);
        }
        nodeInfo.writeVarint(NODE_SDK_LEVEL, Build.VERSION.SDK_INT);

        final ProtoWriter payload = new ProtoWriter();
        payload.writeMessage(PAYLOAD_NODE_INFO, nodeInfo.toByteArray());

        // NodeInfo envelopes carry no field 5 (unlike peer-sync/DataItem) - see reference capture.
        return frame(wrapEnvelope(payload.toByteArray(), false));
    }

    /**
     * Builds a DataItem PUT that changes a boolean {@code protogensettings} watch setting, in the
     * exact wire shape captured from the stock app (research notes §23): a DataItem at
     * {@code wear://<node>/protogensettings/<group>/<key>/<node>} whose value proto is
     * {@code { field1 = { field2 = 0|1 }, field2 = <write time ms> }}, carrying the source
     * package and its signing-cert digest so the watch's trusted_packages check passes.
     *
     * @param sourcePackage the trusted source app package (the watch verifies it)
     * @param signatureHex  that package's signing-certificate SHA digest, as lowercase hex
     * @param ownNodeId     the node this side authors DataItems under (our connection node)
     * @param groupAndKey   e.g. {@code "display/always_on_display"}
     * @param value         the boolean to set
     * @param seq           a monotonically increasing sequence number
     */
    public static byte[] buildProtogenBooleanSettingPut(final String sourcePackage,
                                                        final String signatureHex,
                                                        final String ownNodeId,
                                                        final String groupAndKey,
                                                        final boolean value, final long seq) {
        final ProtoWriter typed = new ProtoWriter();
        typed.writeVarint(PROTOGEN_BOOL, value ? 1 : 0);
        return buildProtogenSettingPut(sourcePackage, signatureHex, ownNodeId, groupAndKey, typed, seq);
    }

    /** int protogen setting (typed inner field 1, e.g. screen_off_timeout_ms, mode_ringer). */
    public static byte[] buildProtogenIntSettingPut(final String sourcePackage, final String signatureHex,
                                                    final String ownNodeId, final String groupAndKey,
                                                    final long value, final long seq) {
        final ProtoWriter typed = new ProtoWriter();
        typed.writeVarint(PROTOGEN_INT, value);
        return buildProtogenSettingPut(sourcePackage, signatureHex, ownNodeId, groupAndKey, typed, seq);
    }

    /** float protogen setting (typed inner field 5, fixed32, e.g. font_scale). */
    public static byte[] buildProtogenFloatSettingPut(final String sourcePackage, final String signatureHex,
                                                      final String ownNodeId, final String groupAndKey,
                                                      final float value, final long seq) {
        final ProtoWriter typed = new ProtoWriter();
        typed.writeFixed32(PROTOGEN_FLOAT, Float.floatToIntBits(value));
        return buildProtogenSettingPut(sourcePackage, signatureHex, ownNodeId, groupAndKey, typed, seq);
    }

    /**
     * Builds a DataItem PUT for a Samsung `w_module_sec_watch_setting` boolean setting - the scheme
     * the watch's StDataLayerListenerService actually applies (research notes §28). Requirements
     * decoded from that service + StDataLayerDisplayManager:
     *   - path wear://<node>/w_module_sec_watch_setting/<sub> where <node> is a CONNECTED peer node
     *     (our connection node, so the watch's isValidNodeId check passes),
     *   - DataMap { "booleanIsChecked": bool, "from": "mobile" } - "from" must not be "watch" or
     *     the service skips it.
     *
     * @param ownNodeId our connection node id (must be a peer node the watch sees connected)
     * @param subPath   e.g. "display/always_on_display", "display/wake_on_touch_screen"
     */
    public static byte[] buildWatchSettingBooleanPut(final String sourcePackage, final String signatureHex,
                                                     final String ownNodeId, final String subPath,
                                                     final boolean value, final long seq) {
        return buildWatchSettingPut(sourcePackage, signatureHex, ownNodeId, subPath,
                "booleanIsChecked", dataMapBool(value), seq);
    }

    /**
     * Writes an int-valued watch setting (e.g. brightness, font size, volume). {@code valueKey} is
     * the setting-specific DataMap key the watch's manager reads - "intLevel" for brightness/font
     * size/volume, "intSelectedItem" for list-index settings like vibration intensity.
     */
    public static byte[] buildWatchSettingIntPut(final String sourcePackage, final String signatureHex,
                                                 final String ownNodeId, final String subPath,
                                                 final String valueKey, final int value, final long seq) {
        return buildWatchSettingPut(sourcePackage, signatureHex, ownNodeId, subPath,
                valueKey, dataMapInt(value), seq);
    }

    /**
     * Writes a string-valued watch setting (e.g. sound_mode "stringMode"). {@code valueKey} is the
     * setting-specific DataMap key the watch's manager reads.
     */
    public static byte[] buildWatchSettingStringPut(final String sourcePackage, final String signatureHex,
                                                    final String ownNodeId, final String subPath,
                                                    final String valueKey, final String value, final long seq) {
        return buildWatchSettingPut(sourcePackage, signatureHex, ownNodeId, subPath,
                valueKey, dataMapString(value), seq);
    }

    /**
     * Builds a DataItem PUT that pushes a phone notification onto the watch's notification stream,
     * in the Wear "notification stream" convention the Watch7 notification bridge renders (research
     * notes §5f/§5g): a DataItem at
     * {@code wear://<node>/bridger/stream_item:<node>:<pkg>:<id>:<tag>} whose DataMap carries the
     * title/text/app-name/meta fields captured from the stock app. Icons and action buttons (which
     * the stock app delivers as separate binary Wear Assets referenced by digest) are omitted - the
     * watch renders a text notification without them.
     *
     * @param ownNodeId   our connection node id (the DataItem host; also embedded in the stream key)
     * @param packageName the posting app's package (stream key + used for grouping on the watch)
     * @param notifId     the notification id (stream key)
     * @param tag         the notification tag, or {@code null} (encoded as "null_tag")
     * @param appName     the posting app's display label (shown as the notification's source)
     * @param title       notification title
     * @param text        notification body text
     * @param subText     optional sub-text (app-provided), or {@code null} to omit
     * @param postTime    post time in epoch millis (post_time_millis)
     * @param whenMs      the notification's {@code when} timestamp in epoch millis
     * @param color       the notification accent color as an ARGB value
     * @param flags       the raw Android {@code Notification.flags} bitmask
     * @param category    the Android notification category (e.g. {@code "call"}), or {@code null}
     * @param seq         our per-node data version (must exceed the watch's counter for our node)
     */
    public static byte[] buildNotificationPut(final String sourcePackage, final String signatureHex,
                                              final String ownNodeId, final String packageName,
                                              final int notifId, final String tag, final String appName,
                                              final String title, final String text, final String subText,
                                              final long postTime, final long whenMs, final long color,
                                              final int flags, final String category, final long seq) {
        return buildNotificationPut(sourcePackage, signatureHex, ownNodeId, packageName, notifId, tag,
                appName, title, text, subText, postTime, whenMs, color, flags, category, seq, null);
    }

    /**
     * As above, plus a {@code wearable_actions} array (each element from {@link #buildWearableAction})
     * so the watch renders action buttons / a quick-reply field. Pass {@code null} or an empty list
     * for a plain notification.
     */
    public static byte[] buildNotificationPut(final String sourcePackage, final String signatureHex,
                                              final String ownNodeId, final String packageName,
                                              final int notifId, final String tag, final String appName,
                                              final String title, final String text, final String subText,
                                              final long postTime, final long whenMs, final long color,
                                              final int flags, final String category, final long seq,
                                              final java.util.List<byte[]> wearableActions) {
        return buildNotificationPut(sourcePackage, signatureHex, ownNodeId, packageName, notifId, tag,
                appName, title, text, subText, postTime, whenMs, color, flags, category, seq,
                wearableActions, null);
    }

    /**
     * As above, plus the app icon. {@code appIconDigest} must be the {@link #assetDigest} of an asset
     * whose chunks ({@link #buildAssetChunks}) were already sent, or {@code null} for no icon.
     */
    public static byte[] buildNotificationPut(final String sourcePackage, final String signatureHex,
                                              final String ownNodeId, final String packageName,
                                              final int notifId, final String tag, final String appName,
                                              final String title, final String text, final String subText,
                                              final long postTime, final long whenMs, final long color,
                                              final int flags, final String category, final long seq,
                                              final java.util.List<byte[]> wearableActions,
                                              final String appIconDigest) {
        final ProtoWriter dataMap = new ProtoWriter();
        dataMapEntry(dataMap, "app_name", dataMapString(nz(appName)));
        dataMapEntry(dataMap, "title_raw", dataMapString(nz(title)));
        dataMapEntry(dataMap, "title_html", dataMapString(nz(title)));
        dataMapEntry(dataMap, "text_raw", dataMapString(nz(text)));
        dataMapEntry(dataMap, "text_html", dataMapString(nz(text)));
        if (subText != null) {
            dataMapEntry(dataMap, "sub_text_raw", dataMapString(subText));
            dataMapEntry(dataMap, "sub_text_html", dataMapString(subText));
        }
        dataMapEntry(dataMap, "post_time_millis", dataMapLong(postTime));
        dataMapEntry(dataMap, "when", dataMapLong(whenMs));
        dataMapEntry(dataMap, "show_when", dataMapBool(true));
        // color is read as an Integer (int32 ARGB) by the watch's DataMap reader, not int64.
        dataMapEntry(dataMap, "color", dataMapInt((int) color));
        dataMapEntry(dataMap, "notification_flags", dataMapInt(flags));
        if (category != null) {
            dataMapEntry(dataMap, "category", dataMapString(category));
        }
        dataMapEntry(dataMap, "notification_style", dataMapInt(1));
        dataMapEntry(dataMap, "defaults", dataMapInt(2));
        dataMapEntry(dataMap, "group_alert_behavior", dataMapInt(0));
        dataMapEntry(dataMap, "has_content_intent", dataMapBool(false));
        dataMapEntry(dataMap, "is_emergency", dataMapBool(false));
        dataMapEntry(dataMap, "is_group_summary", dataMapBool(false));
        dataMapEntry(dataMap, "is_work_profile", dataMapBool(false));
        dataMapEntry(dataMap, "only_alert_once", dataMapBool(false));
        dataMapEntry(dataMap, "user_id", dataMapInt(0));
        // supplemental_data / supplemental_assets are Asset-typed on the watch (icon/picture
        // blobs); we attach no Wear Assets, so they are omitted (sending an int errors on parse).
        // Action buttons: an array (typeTag 10) of action DataMaps (typeTag 9). The stock app puts the
        // notification's own actions under "actions" and only WearableExtender ones under
        // "wearable_actions" - the captured, working reply came through "actions", so that is what we
        // populate; "wearable_actions" is sent empty alongside it, exactly as stock does (§46).
        if (wearableActions != null && !wearableActions.isEmpty()) {
            final java.util.List<byte[]> elements = new java.util.ArrayList<>(wearableActions.size());
            for (final byte[] action : wearableActions) {
                elements.add(dataMapNested(action));
            }
            dataMapEntry(dataMap, "actions", dataMapArray(elements));
            dataMapEntry(dataMap, "wearable_actions", dataMapArray(java.util.Collections.emptyList()));
        }
        // The icon itself travels as a separate Wear Asset; the DataMap only points at the key the
        // DataItem binds below (index 0 here, since the app icon is our only attachment) - §47.
        final java.util.List<String> assetDigests = new java.util.ArrayList<>();
        if (appIconDigest != null) {
            dataMapEntry(dataMap, "app_icon", dataMapAssetRef(assetDigests.size()));
            assetDigests.add(appIconDigest);
        }

        return buildStreamItemDataItem(sourcePackage, signatureHex, ownNodeId, packageName, notifId,
                tag, dataMap.toByteArray(), 0, seq, assetDigests);
    }

    /**
     * Builds the DataItem DELETE that mirrors dismissing a notification (research notes §5g): the
     * same {@code /bridger/stream_item:...} path with the DataItem delete flag (field 6) set to 1
     * and no payload. Dismissing on the phone removes it from the watch's notification list.
     */
    public static byte[] buildNotificationDelete(final String sourcePackage, final String signatureHex,
                                                 final String ownNodeId, final String packageName,
                                                 final int notifId, final String tag, final long seq) {
        return buildStreamItemDataItem(sourcePackage, signatureHex, ownNodeId, packageName, notifId,
                tag, new byte[0], 1, seq);
    }

    private static byte[] buildStreamItemDataItem(final String sourcePackage, final String signatureHex,
                                                  final String ownNodeId, final String packageName,
                                                  final int notifId, final String tag, final byte[] dataMap,
                                                  final int deleteFlag, final long seq) {
        return buildStreamItemDataItem(sourcePackage, signatureHex, ownNodeId, packageName, notifId,
                tag, dataMap, deleteFlag, seq, null);
    }

    /** As above, binding {@code assetDigests} (in index order) to the keys "0", "1", ... (field 8). */
    private static byte[] buildStreamItemDataItem(final String sourcePackage, final String signatureHex,
                                                  final String ownNodeId, final String packageName,
                                                  final int notifId, final String tag, final byte[] dataMap,
                                                  final int deleteFlag, final long seq,
                                                  final java.util.List<String> assetDigests) {
        final String streamTag = tag == null ? "null_tag" : tag;
        final String path = "wear://" + ownNodeId + "/bridger/stream_item:" + ownNodeId + ":"
                + packageName + ":" + notifId + ":" + streamTag;
        final long now = System.currentTimeMillis();

        final ProtoWriter dataItem = new ProtoWriter();
        dataItem.writeString(DATAITEM_SOURCE_PACKAGE, sourcePackage);
        dataItem.writeString(DATAITEM_PATH, path);
        if (deleteFlag == 0) {
            dataItem.writeMessage(DATAITEM_PAYLOAD, dataMap);
        }
        dataItem.writeVarint(DATAITEM_SEQ, seq);
        dataItem.writeVarint(DATAITEM_DELETE_FLAG, deleteFlag);
        dataItem.writeString(DATAITEM_DEST_NODE_ID, ownNodeId);
        if (assetDigests != null) {
            for (int i = 0; i < assetDigests.size(); i++) {
                final ProtoWriter ref = new ProtoWriter();
                ref.writeString(ASSET_BINDING_DIGEST, assetDigests.get(i));
                final ProtoWriter binding = new ProtoWriter();
                binding.writeString(ASSET_BINDING_KEY, String.valueOf(i));
                binding.writeMessage(ASSET_BINDING_REF, ref.toByteArray());
                binding.writeVarint(ASSET_BINDING_KIND, 4);
                dataItem.writeMessage(DATAITEM_ASSETS, binding.toByteArray());
            }
        }
        dataItem.writeString(DATAITEM_SIGNATURE, signatureHex);
        dataItem.writeVarint(DATAITEM_TIMESTAMP, now);

        final ProtoWriter payload = new ProtoWriter();
        payload.writeMessage(PAYLOAD_DATA_ITEM, dataItem.toByteArray());
        return frame(wrapEnvelope(payload.toByteArray(), true));
    }

    private static String nz(final String s) {
        return s == null ? "" : s;
    }

    // MessageApi MessageEvent field layout (payload field 10), decoded live (research notes §36):
    //   {1=seq counter}{2=srcPackage}{3=signingCertDigest}{4=targetNodeId}{5=0}
    //   {6=path}{7=data bytes/JSON}{8=srcNodeId}{10=4}{13=1}
    private static final int ME_SEQ = 1;
    private static final int ME_SRC_PACKAGE = 2;
    private static final int ME_SIGNATURE = 3;
    private static final int ME_TARGET_NODE = 4;
    private static final int ME_FLAG5 = 5;
    private static final int ME_PATH = 6;
    private static final int ME_DATA = 7;
    private static final int ME_SRC_NODE = 8;
    private static final int ME_FLAG10 = 10;
    private static final int ME_FLAG13 = 13;

    /**
     * Builds a MessageApi MessageEvent (one-shot RPC-style message, e.g. the media-controller
     * JSON messages). Unlike a DataItem this is not stored/versioned, so {@code seq} is just a
     * per-connection message counter, not the per-node data version.
     *
     * @param targetNodeId the watch's node id (the message's destination)
     * @param ownNodeId    our node id (the source)
     * @param path         MessageApi path, e.g. {@code "/w_apps_media_controller/mediametadata"}
     * @param data         the payload bytes (for the Samsung channels, a UTF-8 JSON string)
     */
    public static byte[] buildMessageEvent(final String sourcePackage, final String signatureHex,
                                           final String ownNodeId, final String targetNodeId,
                                           final long seq, final String path, final byte[] data) {
        return buildMessageEvent(sourcePackage, signatureHex, ownNodeId, targetNodeId, seq, path,
                data, ME_FLAG10_DEFAULT);
    }

    // Field 10 appears to classify the message; the stock companion uses 4 for the app channels we
    // mirror but 2 for the /bridger/result RPC callback, so that path overrides it (research §46).
    private static final int ME_FLAG10_DEFAULT = 4;
    private static final int ME_FLAG10_RPC_RESULT = 2;

    /** As above, with an explicit field-10 classifier (see {@link #ME_FLAG10_RPC_RESULT}). */
    public static byte[] buildMessageEvent(final String sourcePackage, final String signatureHex,
                                           final String ownNodeId, final String targetNodeId,
                                           final long seq, final String path, final byte[] data,
                                           final int flag10) {
        final ProtoWriter me = new ProtoWriter();
        me.writeVarint(ME_SEQ, seq);
        me.writeString(ME_SRC_PACKAGE, sourcePackage);
        me.writeString(ME_SIGNATURE, signatureHex);
        me.writeString(ME_TARGET_NODE, targetNodeId);
        me.writeVarint(ME_FLAG5, 0);
        me.writeString(ME_PATH, path);
        me.writeMessage(ME_DATA, data);
        me.writeString(ME_SRC_NODE, ownNodeId);
        me.writeVarint(ME_FLAG10, flag10);
        me.writeVarint(ME_FLAG13, 1);

        final ProtoWriter payload = new ProtoWriter();
        payload.writeMessage(PAYLOAD_MESSAGE_EVENT, me.toByteArray());
        return frame(wrapEnvelope(payload.toByteArray(), true));
    }

    private static byte[] extractMessageEvent(final byte[] message) {
        final byte[] envelope = stripLengthPrefix(message);
        if (envelope == null) {
            return null;
        }
        try {
            final byte[] payload = new ProtoReader(envelope).findMessage(FIELD_ENVELOPE_PAYLOAD);
            return payload == null ? null : new ProtoReader(payload).findMessage(PAYLOAD_MESSAGE_EVENT);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** If the message is a MessageEvent, returns its path (field 6), else null. */
    public static String parseMessageEventPath(final byte[] message) {
        final byte[] me = extractMessageEvent(message);
        return me == null ? null : new ProtoReader(me).findString(ME_PATH);
    }

    /** If the message is a MessageEvent, returns its data (field 7) as a UTF-8 string, else null. */
    public static String parseMessageEventData(final byte[] message) {
        final byte[] me = extractMessageEvent(message);
        return me == null ? null : new ProtoReader(me).findString(ME_DATA);
    }

    /** If the message is a MessageEvent, returns its data (field 7) raw bytes, else null. */
    public static byte[] parseMessageEventDataBytes(final byte[] message) {
        final byte[] me = extractMessageEvent(message);
        return me == null ? null : new ProtoReader(me).findMessage(ME_DATA);
    }

    // Besides the batterystate DataItem, the watch pushes its charge level as a plain-JSON
    // MessageEvent on /w_stf whenever it changes (research notes §48):
    //   {"msg_id":"stf_battery_rsp","deviceId":"<mac>","result":true,"data":"{\"batteryLevel\":95}"}
    // Ignoring these is why the level could sit stale for hours while the watch charged.
    private static final String STF_PATH = "/w_stf";
    private static final java.util.regex.Pattern STF_BATTERY_LEVEL =
            java.util.regex.Pattern.compile("batteryLevel\\\\?\"\\s*:\\s*(\\d{1,3})");

    /**
     * Builds the {@code stf_battery_req} MessageEvent that asks the watch to report its charge level
     * on {@code /w_stf} (decoded from the stock {@code STFMessageData}/{@code STFDataLayerUtil}:
     * {"msg_id":"stf_battery_req","deviceId":"<mac>"}, no other fields, no handshake).
     */
    public static byte[] buildStfBatteryRequest(final String sourcePackage, final String signatureHex,
                                                final String ownNodeId, final String targetNodeId,
                                                final long seq, final String deviceMac) {
        final String json = "{\"msg_id\":\"stf_battery_req\",\"deviceId\":\"" + deviceMac + "\"}";
        return buildMessageEvent(sourcePackage, signatureHex, ownNodeId, targetNodeId, seq, STF_PATH,
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The battery percentage from a {@code /w_stf} {@code stf_battery_rsp} push, or null. */
    public static Integer parseStfBatteryLevel(final byte[] message) {
        final byte[] me = extractMessageEvent(message);
        if (me == null) {
            return null;
        }
        final ProtoReader reader = new ProtoReader(me);
        if (!STF_PATH.equals(reader.findString(ME_PATH))) {
            return null;
        }
        final String json = reader.findString(ME_DATA);
        if (json == null || !json.contains("stf_battery_rsp")) {
            return null;
        }
        final java.util.regex.Matcher m = STF_BATTERY_LEVEL.matcher(json);
        if (!m.find()) {
            return null;
        }
        try {
            final int level = Integer.parseInt(m.group(1));
            return level >= 0 && level <= 100 ? level : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // ---- Incoming bridged-action handling (watch -> phone quick reply / action tap), research §46 ----
    // The watch invokes a notification action by sending a MessageEvent on /bridger/rpc whose data is
    // a DataMap { rpc_command:"bridged_action", intent_id:<the action's intent_id>,
    // remote_input_results:{ <return_key>:{ string_result:<reply text> } }, ...invocation_id }.
    public static final String BRIDGER_RPC_PATH = "/bridger/rpc";
    public static final String BRIDGER_RESULT_PATH = "/bridger/result";
    private static final String RPC_INVOCATION_ID =
            "com.google.android.clockwork.actions.RpcWithCallback.invocation_id";

    /** Parsed /bridger/rpc bridged action: the invoked action's intent_id, optional reply, invocation id. */
    public static final class BridgedAction {
        public final String intentId;
        public final String reply;       // null when it was a plain action tap (no remote input)
        public final String invocationId; // echoed back in the /bridger/result ack (may be empty)

        BridgedAction(final String intentId, final String reply, final String invocationId) {
            this.intentId = intentId;
            this.reply = reply;
            this.invocationId = invocationId;
        }
    }

    /**
     * If the message is a {@code /bridger/rpc} {@code bridged_action} MessageEvent, returns the parsed
     * action (intent_id + optional reply text), else {@code null}.
     */
    public static BridgedAction parseBridgedAction(final byte[] message) {
        final byte[] me = extractMessageEvent(message);
        if (me == null) {
            return null;
        }
        final ProtoReader meReader = new ProtoReader(me);
        if (!BRIDGER_RPC_PATH.equals(meReader.findString(ME_PATH))) {
            return null;
        }
        final byte[] data = meReader.findMessage(ME_DATA);
        if (data == null) {
            return null;
        }
        try {
            if (!"bridged_action".equals(getMapString(data, "rpc_command"))) {
                return null;
            }
            final String intentId = getMapString(data, "intent_id");
            if (intentId == null) {
                return null;
            }
            String invocationId = getMapString(data, RPC_INVOCATION_ID);
            if (invocationId == null) {
                invocationId = "";
            }
            return new BridgedAction(intentId, parseFirstRemoteInputResult(data), invocationId);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    // remote_input_results is a nested DataMap keyed by each RemoteInput's return_key; each value is
    // itself a nested DataMap holding string_result (plain) or char_sequence_html. Return the first.
    private static String parseFirstRemoteInputResult(final byte[] dataMap) {
        final byte[] results = findContainerInMap(dataMap, "remote_input_results");
        if (results == null) {
            return null;
        }
        for (final byte[] entry : new ProtoReader(results).findAllMessages(NESTED_MAP_ENTRY_FIELD)) {
            final byte[] typedValue = new ProtoReader(entry).findMessage(2);
            final byte[] sub = typedValue == null ? null : new ProtoReader(typedValue).findMessage(2);
            if (sub == null) {
                continue;
            }
            final String plain = getNestedString(sub, "string_result");
            if (plain != null) {
                return plain;
            }
            final String html = getNestedString(sub, "char_sequence_html");
            if (html != null) {
                return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString();
            }
        }
        return null;
    }

    // Reads a string entry out of a nested DataMap's container (its entries live at field 9).
    private static String getNestedString(final byte[] container, final String key) {
        for (final byte[] entry : new ProtoReader(container).findAllMessages(NESTED_MAP_ENTRY_FIELD)) {
            if (!key.equals(new ProtoReader(entry).findString(1))) {
                continue;
            }
            final byte[] typedValue = new ProtoReader(entry).findMessage(2);
            final byte[] valueContainer = typedValue == null ? null
                    : new ProtoReader(typedValue).findMessage(2);
            return valueContainer == null ? null : new ProtoReader(valueContainer).findString(2);
        }
        return null;
    }

    /**
     * Builds the {@code /bridger/result} ack MessageEvent the phone returns after handling a bridged
     * action, echoing the invocation id and reporting success (mirrors the stock companion).
     */
    public static byte[] buildBridgedActionResult(final String sourcePackage, final String signatureHex,
                                                  final String ownNodeId, final String targetNodeId,
                                                  final long seq, final String invocationId,
                                                  final boolean success) {
        final ProtoWriter result = new ProtoWriter();
        nestedMapEntry(result, "com.google.android.clockwork.actions.RpcWithCallback.message_id",
                dataMapInt(1));
        nestedMapEntry(result, "com.google.android.clockwork.actions.RpcWithCallback.successful",
                dataMapBool(success));
        final ProtoWriter ack = new ProtoWriter();
        dataMapEntry(ack, RPC_INVOCATION_ID, dataMapString(nz(invocationId)));
        dataMapEntry(ack, "com.google.android.clockwork.actions.RpcWithCallback.result",
                dataMapNested(result.toByteArray()));
        return buildMessageEvent(sourcePackage, signatureHex, ownNodeId, targetNodeId, seq,
                BRIDGER_RESULT_PATH, ack.toByteArray(), ME_FLAG10_RPC_RESULT);
    }

    // Reads a string DataMap value ({f1=2, f2={f2=str}}) directly from raw DataMap bytes, or null.
    private static String getMapString(final byte[] dataMap, final String key) {
        final byte[] container = findContainerInMap(dataMap, key);
        return container == null ? null : new ProtoReader(container).findString(2);
    }

    // Finds the typedValue container (typedValue.f2) for a key within raw DataMap bytes, or null.
    private static byte[] findContainerInMap(final byte[] dataMap, final String key) {
        if (dataMap == null) {
            return null;
        }
        for (final byte[] entry : new ProtoReader(dataMap).findAllMessages(1)) {
            if (!key.equals(new ProtoReader(entry).findString(1))) {
                continue;
            }
            final byte[] typedValue = new ProtoReader(entry).findMessage(2);
            return typedValue == null ? null : new ProtoReader(typedValue).findMessage(2);
        }
        return null;
    }

    private static byte[] buildWatchSettingPut(final String sourcePackage, final String signatureHex,
                                               final String ownNodeId, final String subPath,
                                               final String valueKey, final byte[] typedValue, final long seq) {
        final ProtoWriter dataMap = new ProtoWriter();
        dataMapEntry(dataMap, valueKey, typedValue);
        dataMapEntry(dataMap, "from", dataMapString("mobile"));

        final String path = "wear://" + ownNodeId + "/w_module_sec_watch_setting/" + subPath;
        final long now = System.currentTimeMillis();

        final ProtoWriter dataItem = new ProtoWriter();
        dataItem.writeString(DATAITEM_SOURCE_PACKAGE, sourcePackage);
        dataItem.writeString(DATAITEM_PATH, path);
        dataItem.writeMessage(DATAITEM_PAYLOAD, dataMap.toByteArray());
        dataItem.writeVarint(DATAITEM_SEQ, seq);
        dataItem.writeVarint(DATAITEM_DELETE_FLAG, 0);
        dataItem.writeString(DATAITEM_DEST_NODE_ID, ownNodeId);
        dataItem.writeString(DATAITEM_SIGNATURE, signatureHex);
        dataItem.writeVarint(DATAITEM_TIMESTAMP, now);

        final ProtoWriter payload = new ProtoWriter();
        payload.writeMessage(PAYLOAD_DATA_ITEM, dataItem.toByteArray());
        return frame(wrapEnvelope(payload.toByteArray(), true));
    }

    // Wear DataMap "TypedValue": field1 = type tag, field2 = a container whose inner field number
    // is the type tag (bool = field 8, string = field 2). A DataMap is repeated entries at field 1,
    // each { field1 = key, field2 = typed value }.
    private static void dataMapEntry(final ProtoWriter dataMap, final String key, final byte[] typedValue) {
        dataMapEntryAt(dataMap, DATAMAP_ENTRY_FIELD, key, typedValue);
    }

    // A top-level DataMap carries its entries at field 1; a nested DataMap *value* carries them at
    // field 9 inside the typed value's container (see dataMapNested) - verified against the stock
    // capture's populated notification "actions" array (research notes §46).
    private static final int DATAMAP_ENTRY_FIELD = 1;
    private static final int NESTED_MAP_ENTRY_FIELD = 9;

    private static void dataMapEntryAt(final ProtoWriter dataMap, final int entryField,
                                       final String key, final byte[] typedValue) {
        final ProtoWriter entry = new ProtoWriter();
        entry.writeString(1, key);
        entry.writeMessage(2, typedValue);
        dataMap.writeMessage(entryField, entry.toByteArray());
    }

    /** Adds an entry to a nested DataMap value's container (entries live at field 9 there). */
    private static void nestedMapEntry(final ProtoWriter container, final String key, final byte[] typedValue) {
        dataMapEntryAt(container, NESTED_MAP_ENTRY_FIELD, key, typedValue);
    }

    private static byte[] dataMapBool(final boolean value) {
        final ProtoWriter container = new ProtoWriter();
        container.writeVarint(8, value ? 1 : 0);
        final ProtoWriter tv = new ProtoWriter();
        tv.writeVarint(1, 8);
        tv.writeMessage(2, container.toByteArray());
        return tv.toByteArray();
    }

    private static byte[] dataMapString(final String s) {
        final ProtoWriter container = new ProtoWriter();
        container.writeString(2, s);
        final ProtoWriter tv = new ProtoWriter();
        tv.writeVarint(1, 2);
        tv.writeMessage(2, container.toByteArray());
        return tv.toByteArray();
    }

    private static byte[] dataMapInt(final int value) {
        final ProtoWriter container = new ProtoWriter();
        container.writeVarint(6, value);
        final ProtoWriter tv = new ProtoWriter();
        tv.writeVarint(1, 6);
        tv.writeMessage(2, container.toByteArray());
        return tv.toByteArray();
    }

    // long-valued DataMap entry (typeTag 5, container inner field 5, varint) - used for the
    // notification time/color fields that the stock app encodes as int64 (research notes §5g).
    private static byte[] dataMapLong(final long value) {
        final ProtoWriter container = new ProtoWriter();
        container.writeVarint(5, value);
        final ProtoWriter tv = new ProtoWriter();
        tv.writeVarint(1, 5);
        tv.writeMessage(2, container.toByteArray());
        return tv.toByteArray();
    }

    // Composite DataMap value types, following the same {f1=typeTag, f2=container} pattern where the
    // container's inner field number equals the typeTag (research notes §46, decoded from the stock
    // capture's populated notification "actions" array):
    //   typeTag 9  = embedded DataMap  (container = the map's entries, repeated at field 9)
    //   typeTag 10 = array/list        (container = repeated field 10, each a full TypedValue)
    //   typeTag 11 = String[]          (container = repeated field 11; stock sends it empty)
    //   typeTag 13 = Wear Asset ref    (container inner field 13 = the attached-asset index, varint)

    /**
     * A nested-DataMap DataMap value (typeTag 9). {@code entries} must already hold the map's entries
     * written at field 9 (use {@link #nestedMapEntry}).
     */
    private static byte[] dataMapNested(final byte[] entries) {
        final ProtoWriter tv = new ProtoWriter();
        tv.writeVarint(1, 9);
        tv.writeMessage(2, entries);
        return tv.toByteArray();
    }

    /**
     * The identifier a DataItem uses to bind an attached asset: base64url of the SHA-1 of the whole
     * asset, unpadded (27 chars) - research notes §47.
     */
    public static String assetDigest(final byte[] asset) {
        try {
            final byte[] sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(asset);
            return android.util.Base64.encodeToString(sha1,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /**
     * Splits an asset into the framed chunk messages that carry it to the watch (research notes §47).
     * Every chunk repeats the same transfer id; only the final one carries the digest and the
     * last-chunk flag. Send these before the DataItem that references the asset.
     *
     * @param asset      the encoded bytes (the stock app sends PNG and WEBP)
     * @param transferId an id shared by this asset's chunks; the digest is what the DataItem binds
     */
    public static java.util.List<byte[]> buildAssetChunks(final byte[] asset, final String transferId) {
        final String digest = assetDigest(asset);
        final java.util.List<byte[]> out = new java.util.ArrayList<>();
        int offset = 0;
        do {
            final int len = Math.min(ASSET_CHUNK_SIZE, asset.length - offset);
            final boolean last = offset + len >= asset.length;

            final ProtoWriter chunk = new ProtoWriter();
            chunk.writeString(ASSET_TRANSFER_ID, transferId);
            chunk.writeVarint(ASSET_LAST_CHUNK, last ? 1 : 0);
            chunk.writeMessage(ASSET_BYTES, java.util.Arrays.copyOfRange(asset, offset, offset + len));
            if (last) {
                chunk.writeString(ASSET_DIGEST, digest);
            }

            final ProtoWriter payload = new ProtoWriter();
            payload.writeMessage(PAYLOAD_ASSET, chunk.toByteArray());
            // Asset envelopes carry no field 5, unlike DataItem/peer-sync ones (735 of them in the
            // reference capture have {3=1, 4=1} and no field 5); sending it makes the watch ignore
            // the asset, and with it the notification that references it.
            out.add(frame(wrapEnvelope(payload.toByteArray(), false)));
            offset += len;
        } while (offset < asset.length);
        return out;
    }

    /** An Asset-reference DataMap value (typeTag 13): the index of a key bound in DataItem field 8. */
    private static byte[] dataMapAssetRef(final int index) {
        final ProtoWriter container = new ProtoWriter();
        container.writeVarint(13, index);
        final ProtoWriter tv = new ProtoWriter();
        tv.writeVarint(1, 13);
        tv.writeMessage(2, container.toByteArray());
        return tv.toByteArray();
    }

    /** An empty String[] DataMap value (typeTag 11), as the stock app sends for {@code choices_html}. */
    private static byte[] dataMapEmptyStringArray() {
        final ProtoWriter tv = new ProtoWriter();
        tv.writeVarint(1, 11);
        tv.writeMessage(2, new byte[0]);
        return tv.toByteArray();
    }

    /** An array DataMap value (typeTag 10) whose elements are the given already-typed values. */
    private static byte[] dataMapArray(final java.util.List<byte[]> elementTypedValues) {
        final ProtoWriter container = new ProtoWriter();
        for (final byte[] el : elementTypedValues) {
            container.writeMessage(10, el);
        }
        final ProtoWriter tv = new ProtoWriter();
        tv.writeVarint(1, 10);
        tv.writeMessage(2, container.toByteArray());
        return tv.toByteArray();
    }

    /**
     * Builds one wearable-action DataMap for the {@code wearable_actions} array of a notification
     * (research notes §46; shape verified against the stock com.google.android.gms.internal.
     * wear_companion action serializer). Each action carries a display {@code title_html}, an
     * {@code intent_id} the watch echoes back when the action is invoked, and - for a reply action -
     * a single {@code remote_inputs} entry that makes the watch offer a free-form text field.
     *
     * @param titleHtml the action button label
     * @param intentId  an id the watch returns verbatim on invocation (we encode the GB action handle)
     * @param isReply   true to attach a free-form text remote input (quick reply)
     * @param returnKey the RemoteInput result key the watch tags the reply text with
     */
    public static byte[] buildWearableAction(final String titleHtml, final String intentId,
                                             final boolean isReply, final String returnKey) {
        // Field set and order mirror the stock capture exactly (keys are serialized alphabetically).
        final ProtoWriter action = new ProtoWriter();
        nestedMapEntry(action, "action_inline", dataMapBool(false));
        nestedMapEntry(action, "allow_generated_replies", dataMapBool(isReply));
        nestedMapEntry(action, "intent_id", dataMapString(nz(intentId)));
        nestedMapEntry(action, "remote_content_intent", dataMapBool(false));
        final java.util.List<byte[]> remoteInputs = new java.util.ArrayList<>();
        if (isReply) {
            final ProtoWriter ri = new ProtoWriter();
            nestedMapEntry(ri, "allowFreeFormInput", dataMapBool(true));
            nestedMapEntry(ri, "choices_html", dataMapEmptyStringArray());
            nestedMapEntry(ri, "label_html", dataMapString(nz(titleHtml)));
            nestedMapEntry(ri, "return_key", dataMapString(nz(returnKey)));
            remoteInputs.add(dataMapNested(ri.toByteArray()));
        }
        nestedMapEntry(action, "remote_inputs", dataMapArray(remoteInputs));
        nestedMapEntry(action, "title_html", dataMapString(nz(titleHtml)));
        return action.toByteArray();
    }

    private static byte[] buildProtogenSettingPut(final String sourcePackage, final String signatureHex,
                                                  final String ownNodeId, final String groupAndKey,
                                                  final ProtoWriter typedValue, final long seq) {
        final long now = System.currentTimeMillis();

        final ProtoWriter valueProto = new ProtoWriter();
        valueProto.writeMessage(PROTOGEN_VALUE, typedValue.toByteArray());
        valueProto.writeVarint(PROTOGEN_TIMESTAMP, now);

        final String path = "wear://" + ownNodeId + "/protogensettings/" + groupAndKey + "/" + ownNodeId;

        final ProtoWriter dataItem = new ProtoWriter();
        dataItem.writeString(DATAITEM_SOURCE_PACKAGE, sourcePackage);
        dataItem.writeString(DATAITEM_PATH, path);
        dataItem.writeMessage(DATAITEM_PAYLOAD, valueProto.toByteArray());
        dataItem.writeVarint(DATAITEM_SEQ, seq);
        dataItem.writeVarint(DATAITEM_DELETE_FLAG, 0);
        dataItem.writeString(DATAITEM_DEST_NODE_ID, ownNodeId);
        dataItem.writeString(DATAITEM_SIGNATURE, signatureHex);
        dataItem.writeVarint(DATAITEM_TIMESTAMP, now);

        final ProtoWriter payload = new ProtoWriter();
        payload.writeMessage(PAYLOAD_DATA_ITEM, dataItem.toByteArray());

        return frame(wrapEnvelope(payload.toByteArray(), true));
    }

    /**
     * Decodes a protogensettings value proto (DataItem field 4) to a readable string, and returns
     * the boolean if it is a bool setting. Format: {@code { field1 = { f1=int | f2=bool | f5=float }, field2 = ts }}.
     */
    public static Boolean parseProtogenBool(final byte[] valueProto) {
        if (valueProto == null) {
            return null;
        }
        try {
            final byte[] typed = new ProtoReader(valueProto).findMessage(PROTOGEN_VALUE);
            if (typed == null) {
                return null;
            }
            final Long b = new ProtoReader(typed).findVarint(PROTOGEN_BOOL);
            return b == null ? null : (b != 0);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** If the message is a DataItem, returns its path (field 2), else null. */
    public static String parseDataItemPath(final byte[] message) {
        final byte[] dataItem = extractDataItem(message);
        return dataItem == null ? null : new ProtoReader(dataItem).findString(DATAITEM_PATH);
    }

    /** If the message is a DataItem, returns its value proto (field 4) bytes, else null. */
    public static byte[] parseDataItemValue(final byte[] message) {
        final byte[] dataItem = extractDataItem(message);
        return dataItem == null ? null : new ProtoReader(dataItem).findMessage(DATAITEM_PAYLOAD);
    }

    /** If the message is a DataItem, returns its per-node sequence number (field 5), else null. */
    public static Long parseDataItemSeq(final byte[] message) {
        final byte[] dataItem = extractDataItem(message);
        return dataItem == null ? null : new ProtoReader(dataItem).findVarint(DATAITEM_SEQ);
    }

    /**
     * If the message is the watch's `batterystate/data/batterystate` DataItem, returns
     * {@code {level, status, timestampMs}} where level is 0-100 (proto field 1, verified against
     * dumpsys), status is the AOSP BatteryManager status (field 5: 2=charging, 3=discharging,
     * 5=full; -1 if absent), and timestampMs is the reading's own epoch-millis timestamp (field 7;
     * -1 if absent). Returns {@code null} for other messages.
     * <p/>
     * The timestamp matters because the watch keeps DataItems from more than one node identity (a
     * stale one left over from an earlier enrolment plus the current one). Both publish a
     * batterystate; the stale one carries an old value and an old timestamp. Callers must prefer the
     * newest reading by timestamp rather than whichever arrives last on the wire, or a days-old
     * cached level silently overwrites the live one.
     */
    public static long[] parseBatteryState(final byte[] message) {
        final String path = parseDataItemPath(message);
        if (path == null || !path.contains("/batterystate/data/batterystate")) {
            return null;
        }
        final byte[] value = parseDataItemValue(message);
        if (value == null) {
            return null;
        }
        final Long level = new ProtoReader(value).findVarint(1);
        if (level == null || level < 0 || level > 100) {
            return null;
        }
        final Long status = new ProtoReader(value).findVarint(5);
        final Long timestamp = new ProtoReader(value).findVarint(7);
        return new long[]{level, status == null ? -1 : status, timestamp == null ? -1 : timestamp};
    }

    /**
     * If the message is a DELETE of one of our {@code /bridger/stream_item:...} notification
     * DataItems (i.e. a notification was dismissed on the watch, mirrored back to us), returns the
     * notification id embedded in the stream key, else null. A delete has the DataItem delete flag
     * (field 6) set or carries no payload. The path may be percent-encoded ({@code %3A} for ':').
     */
    public static Integer parseNotificationStreamDeleteId(final byte[] message) {
        final byte[] dataItem = extractDataItem(message);
        if (dataItem == null) {
            return null;
        }
        try {
            final String path = new ProtoReader(dataItem).findString(DATAITEM_PATH);
            if (path == null || !path.contains("/bridger/stream_item")) {
                return null;
            }
            final Long deleteFlag = new ProtoReader(dataItem).findVarint(DATAITEM_DELETE_FLAG);
            final byte[] payload = new ProtoReader(dataItem).findMessage(DATAITEM_PAYLOAD);
            final boolean isDelete = (deleteFlag != null && deleteFlag != 0) || payload == null;
            if (!isDelete) {
                return null;
            }
            // key = "stream_item:<node>:<pkg>:<id>:<tag>" (colons possibly encoded as %3A)
            final int marker = path.indexOf("stream_item");
            String key = path.substring(marker).replace("%3A", ":");
            final int colon = key.indexOf(':');
            if (colon < 0) {
                return null;
            }
            final String[] parts = key.substring(colon + 1).split(":");
            if (parts.length < 3) {
                return null;
            }
            return Integer.parseInt(parts[2]); // <node>:<pkg>:<id>:<tag> -> index 2 = id
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * If the message is a `w_module_sec_watch_setting` DataItem, returns its sub-path (the part
     * after `/w_module_sec_watch_setting/`), else null.
     */
    public static String parseWatchSettingSubPath(final byte[] message) {
        final String path = parseDataItemPath(message);
        if (path == null) {
            return null;
        }
        final String marker = "/w_module_sec_watch_setting/";
        final int idx = path.indexOf(marker);
        return idx < 0 ? null : path.substring(idx + marker.length());
    }

    /**
     * If the message is a peer-sync (SeqAck), returns the data-version counter the watch reports for
     * the given node id, else null. The watch will ignore a DataItem we host under our node unless
     * its seq is greater than this counter (it is the watch's record of our node's data version), so
     * our writes must start above it. Peer-sync: field8 payload with repeated node-refs at field 2,
     * each {f1 = nodeId, f2 = counter}.
     */
    public static Long parsePeerSyncNodeCounter(final byte[] message, final String nodeId) {
        final byte[] envelope = stripLengthPrefix(message);
        if (envelope == null) {
            return null;
        }
        try {
            final byte[] payload = new ProtoReader(envelope).findMessage(FIELD_ENVELOPE_PAYLOAD);
            if (payload == null) {
                return null;
            }
            final byte[] seqAck = new ProtoReader(payload).findMessage(PAYLOAD_SEQ_ACK);
            if (seqAck == null) {
                return null;
            }
            for (final byte[] ref : new ProtoReader(seqAck).findAllMessages(SEQACK_NODE_REF)) {
                if (nodeId.equals(new ProtoReader(ref).findString(NODE_REF_ID))) {
                    return new ProtoReader(ref).findVarint(NODE_REF_COUNTER);
                }
            }
        } catch (final RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * Reads a boolean DataMap entry (e.g. "booleanIsChecked") from a DataItem's value. DataMap
     * wire format: repeated entries at field 1, each {f1=key, f2=typedValue}; a bool typedValue is
     * {f1=8, f2={f8=0|1}}. Returns null if the key is absent.
     */
    public static Boolean parseDataMapBoolean(final byte[] message, final String key) {
        final byte[] dataMap = parseDataItemValue(message);
        if (dataMap == null) {
            return null;
        }
        try {
            for (final byte[] entry : new ProtoReader(dataMap).findAllMessages(1)) {
                final ProtoReader er = new ProtoReader(entry);
                if (!key.equals(er.findString(1))) {
                    continue;
                }
                final byte[] typedValue = new ProtoReader(entry).findMessage(2);
                if (typedValue == null) {
                    return null;
                }
                final byte[] container = new ProtoReader(typedValue).findMessage(2);
                if (container == null) {
                    return null;
                }
                final Long b = new ProtoReader(container).findVarint(8);
                return b == null ? null : (b != 0);
            }
        } catch (final RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * Reads an int DataMap entry (e.g. "intLevel", "intSelectedItem") from a DataItem's value. An
     * int typedValue is {f1=6, f2={f6=varint}}. Returns null if the key is absent.
     */
    public static Integer parseDataMapInt(final byte[] message, final String key) {
        final byte[] container = findDataMapContainer(message, key);
        if (container == null) {
            return null;
        }
        final Long v = new ProtoReader(container).findVarint(6);
        return v == null ? null : v.intValue();
    }

    /**
     * Reads a string DataMap entry (e.g. "stringMode") from a DataItem's value. A string typedValue
     * is {f1=2, f2={f2=string}}. Returns null if the key is absent.
     */
    public static String parseDataMapString(final byte[] message, final String key) {
        final byte[] container = findDataMapContainer(message, key);
        return container == null ? null : new ProtoReader(container).findString(2);
    }

    /**
     * Reads an array DataMap entry (e.g. "arrayItemList" in a {@code .../list} setting) from a
     * DataItem's value, as a list of display labels in index order. The array typedValue is
     * {f1=10, f2={f10=element (repeated)}}; each element is itself a Wear TypedValue
     * ({f1=typeTag, f2=container}), NOT a bare string - so string options are {typeTag 2, {f2=str}}
     * and numeric options (e.g. screen-timeout seconds) are {typeTag 6, {f6=int}}. We render strings
     * as-is and numbers as their decimal value. Some string entries are {@code "value::label"};
     * callers may split on "::". Returns {@code null} if the key is absent.
     */
    public static java.util.List<String> parseDataMapStringArray(final byte[] message, final String key) {
        final byte[] container = findDataMapContainer(message, key);
        if (container == null) {
            return null;
        }
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (final byte[] element : new ProtoReader(container).findAllMessages(10)) {
            final byte[] inner = new ProtoReader(element).findMessage(2);
            String label = null;
            if (inner != null) {
                label = new ProtoReader(inner).findString(2); // string option (typeTag 2)
                if (label == null) {
                    Long n = new ProtoReader(inner).findVarint(6); // int option (typeTag 6)
                    if (n == null) {
                        n = new ProtoReader(inner).findVarint(5); // long option (typeTag 5)
                    }
                    if (n != null) {
                        label = String.valueOf(n);
                    }
                }
            }
            out.add(label != null ? label : "");
        }
        return out;
    }

    /** Finds the typedValue container (typedValue.f2) for the given DataMap key, or null. */
    private static byte[] findDataMapContainer(final byte[] message, final String key) {
        final byte[] dataMap = parseDataItemValue(message);
        if (dataMap == null) {
            return null;
        }
        try {
            for (final byte[] entry : new ProtoReader(dataMap).findAllMessages(1)) {
                if (!key.equals(new ProtoReader(entry).findString(1))) {
                    continue;
                }
                final byte[] typedValue = new ProtoReader(entry).findMessage(2);
                return typedValue == null ? null : new ProtoReader(typedValue).findMessage(2);
            }
        } catch (final RuntimeException e) {
            return null;
        }
        return null;
    }

    private static byte[] extractDataItem(final byte[] message) {
        final byte[] envelope = stripLengthPrefix(message);
        if (envelope == null) {
            return null;
        }
        try {
            final byte[] payload = new ProtoReader(envelope).findMessage(FIELD_ENVELOPE_PAYLOAD);
            if (payload == null) {
                return null;
            }
            return new ProtoReader(payload).findMessage(PAYLOAD_DATA_ITEM);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * Builds the peer-sync ("SeqAck") message the Data Layer expects immediately after the
     * NodeInfo handshake. In the reference capture the phone sends this right after its own
     * NodeInfo; sending a malformed one (a single stale node id + a garbage sentinel) is what
     * made the watch close the channel ~100ms after connecting (research notes §14).
     * <p/>
     * A correct message announces the set of Data Layer nodes this side knows about, each with
     * the highest sequence number it has seen from that node. For a fresh Gadgetbridge session
     * with no sync history the honest, minimal-viable set is {@code cloud}, our own node and the
     * watch's node, all at counter 0.
     *
     * @param ownNodeId   this phone node's id (the same id sent in the NodeInfo handshake)
     * @param watchNodeId the watch's node id, learned by parsing its NodeInfo reply
     *                    ({@link #parseWatchNodeId(byte[])})
     */
    public static byte[] buildSeqAck(final String ownNodeId, final String watchNodeId) {
        final ProtoWriter seqAck = new ProtoWriter();
        // field 1: the highest sequence seen from the peer. A fresh session with no history sends
        // 0xffffffffffffffff ("I have nothing - send me everything"); confirmed on the wire from
        // BOTH sides of a real enrolled session (research notes §16).
        seqAck.writeVarint(SEQACK_COUNTER, -1L);
        appendNodeRef(seqAck, CLOUD_NODE_ID, 0);
        appendNodeRef(seqAck, ownNodeId, 0);
        appendNodeRef(seqAck, watchNodeId, 0);
        seqAck.writeVarint(SEQACK_FLAG, 2);

        final ProtoWriter payload = new ProtoWriter();
        payload.writeMessage(PAYLOAD_SEQ_ACK, seqAck.toByteArray());

        return frame(wrapEnvelope(payload.toByteArray(), true));
    }

    /**
     * Fuller peer-sync that declares every node this side has observed with its current counter
     * (like the stock client, whose peer-sync lists all phone nodes, cloud and the watch - not
     * just three at counter 0). field 1 is our counter for the watch. Used to assert ownership of
     * the phone-side settings node before writing a setting (research notes §26).
     */
    public static byte[] buildSeqAck(final java.util.Map<String, Long> nodeCounters,
                                     final String watchNodeId) {
        final ProtoWriter seqAck = new ProtoWriter();
        final Long watchCounter = nodeCounters.get(watchNodeId);
        seqAck.writeVarint(SEQACK_COUNTER, watchCounter != null ? watchCounter : -1L);
        if (!nodeCounters.containsKey(CLOUD_NODE_ID)) {
            appendNodeRef(seqAck, CLOUD_NODE_ID, 0);
        }
        for (final java.util.Map.Entry<String, Long> e : nodeCounters.entrySet()) {
            appendNodeRef(seqAck, e.getKey(), e.getValue());
        }
        seqAck.writeVarint(SEQACK_FLAG, 2);

        final ProtoWriter payload = new ProtoWriter();
        payload.writeMessage(PAYLOAD_SEQ_ACK, seqAck.toByteArray());

        return frame(wrapEnvelope(payload.toByteArray(), true));
    }

    private static void appendNodeRef(final ProtoWriter seqAck, final String nodeId, final long counter) {
        final ProtoWriter nodeRef = new ProtoWriter();
        nodeRef.writeString(NODE_REF_ID, nodeId);
        nodeRef.writeVarint(NODE_REF_COUNTER, counter);
        seqAck.writeMessage(SEQACK_NODE_REF, nodeRef.toByteArray());
    }

    private static byte[] wrapEnvelope(final byte[] payload, final boolean withFlagC) {
        final ProtoWriter envelope = new ProtoWriter();
        envelope.writeMessage(FIELD_ENVELOPE_PAYLOAD, payload);
        // Field 2 is NOT a random request id (as first assumed) - it is a message-integrity
        // digest that the watch's Wear transport verifies: base64url(SHA-1(payload)), no padding.
        // A mismatch makes the watch abort with "message digest should match the received digest"
        // and tear the connection down. Confirmed byte-for-byte against the reference capture
        // (research notes §22).
        envelope.writeString(FIELD_ENVELOPE_REQUEST_ID, payloadDigest(payload));
        envelope.writeVarint(FIELD_ENVELOPE_FLAG_A, 1);
        envelope.writeVarint(FIELD_ENVELOPE_FLAG_B, 1);
        if (withFlagC) {
            envelope.writeVarint(FIELD_ENVELOPE_FLAG_C, 3);
        }
        return envelope.toByteArray();
    }

    /** base64url(SHA-1(payload)) without padding - the Wear transport's per-message digest. */
    private static String payloadDigest(final byte[] payload) {
        try {
            final byte[] sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(payload);
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sha1);
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    /**
     * Parses an incoming Data Layer message and, if it is a NodeInfo message, returns the
     * sender's node id. Used to learn the watch's current node id from the NodeInfo reply it
     * sends right after our handshake, instead of hardcoding it (the id changes across factory
     * resets / re-pairings).
     * <p/>
     * The message may or may not still carry the 4-byte big-endian length prefix; both are
     * handled. Returns {@code null} if the bytes are not a well-formed NodeInfo message.
     *
     * @param message raw bytes as delivered by the socket read
     */
    public static String parseWatchNodeId(final byte[] message) {
        final byte[] nodeInfo = extractNodeInfo(message);
        if (nodeInfo == null) {
            return null;
        }
        try {
            // NodeInfo field 1 = node id (string)
            return new ProtoReader(nodeInfo).findString(NODE_ID);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * If the message is a NodeInfo, returns its field 7 (the shared connection long-id). Echo
     * this back in our own NodeInfo on the next connect so the watch accepts us as a known peer.
     *
     * @param message raw bytes as delivered by the socket read
     */
    public static String parseWatchConnectionId(final byte[] message) {
        final byte[] nodeInfo = extractNodeInfo(message);
        if (nodeInfo == null) {
            return null;
        }
        try {
            return new ProtoReader(nodeInfo).findString(NODE_CONNECTION_ID);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * Decodes one incoming envelope (4-byte length prefix stripped if present) into a short,
     * human-readable description of its payload - for logging what the watch sends. DataItem
     * payloads include their {@code wear://…} path and source package.
     */
    public static String describeMessage(final byte[] message) {
        final byte[] envelope = stripLengthPrefix(message);
        if (envelope == null) {
            return "empty";
        }
        try {
            final byte[] payload = new ProtoReader(envelope).findMessage(FIELD_ENVELOPE_PAYLOAD);
            if (payload == null) {
                return "non-envelope (" + envelope.length + "B)";
            }
            final ProtoReader p = new ProtoReader(payload);
            if (p.findMessage(PAYLOAD_NODE_INFO) != null) {
                final byte[] ni = new ProtoReader(payload).findMessage(PAYLOAD_NODE_INFO);
                return "NodeInfo node=" + new ProtoReader(ni).findString(NODE_ID);
            }
            if (new ProtoReader(payload).findMessage(PAYLOAD_SEQ_ACK) != null) {
                return "peer-sync";
            }
            final byte[] dataItem = new ProtoReader(payload).findMessage(PAYLOAD_DATA_ITEM);
            if (dataItem != null) {
                final String path = new ProtoReader(dataItem).findString(DATAITEM_PATH);
                final byte[] value = new ProtoReader(dataItem).findMessage(DATAITEM_PAYLOAD);
                final String valHex = value == null ? "-" : toHex(value, 40);
                return "DataItem path=" + path + " val=" + valHex;
            }
            final byte[] messageEvent = new ProtoReader(payload).findMessage(PAYLOAD_MESSAGE_EVENT);
            if (messageEvent != null) {
                return "MessageEvent " + describeMessageEvent(messageEvent);
            }
            return "payload(other, " + payload.length + "B)";
        } catch (final RuntimeException e) {
            return "undecodable (" + envelope.length + "B)";
        }
    }

    // Walks a MessageEvent submessage and prints each field as {num=…}: length-delimited fields
    // are shown as text when they look printable (path / JSON payload) else as a hex preview, and
    // varints as their value. Used to reverse the MessageApi wire layout (path vs data field #s).
    private static String describeMessageEvent(final byte[] messageEvent) {
        final StringBuilder sb = new StringBuilder();
        int pos = 0;
        final int end = messageEvent.length;
        try {
            while (pos < end) {
                long tag = 0;
                int shift = 0;
                while (pos < end) {
                    final byte b = messageEvent[pos++];
                    tag |= (long) (b & 0x7f) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                final int field = (int) (tag >>> 3);
                final int wire = (int) (tag & 0x7);
                if (wire == 2) {
                    int len = 0; shift = 0;
                    while (pos < end) {
                        final byte b = messageEvent[pos++];
                        len |= (b & 0x7f) << shift;
                        if ((b & 0x80) == 0) break;
                        shift += 7;
                    }
                    if (len < 0 || pos + len > end) break;
                    final byte[] v = java.util.Arrays.copyOfRange(messageEvent, pos, pos + len);
                    pos += len;
                    sb.append(" {").append(field).append("=").append(isPrintable(v)
                            ? "\"" + new String(v, StandardCharsets.UTF_8) + "\"" : "hex:" + toHex(v, 60)).append("}");
                } else if (wire == 0) {
                    long val = 0; shift = 0;
                    while (pos < end) {
                        final byte b = messageEvent[pos++];
                        val |= (long) (b & 0x7f) << shift;
                        if ((b & 0x80) == 0) break;
                        shift += 7;
                    }
                    sb.append(" {").append(field).append("#=").append(val).append("}");
                } else if (wire == 1) {
                    pos += 8;
                } else if (wire == 5) {
                    pos += 4;
                } else {
                    break;
                }
            }
        } catch (final RuntimeException e) {
            sb.append(" <decode-error>");
        }
        return sb.toString().trim();
    }

    private static boolean isPrintable(final byte[] b) {
        if (b.length == 0) return false;
        for (final byte x : b) {
            final int c = x & 0xff;
            if (c < 0x09 || (c > 0x0d && c < 0x20) || c > 0x7e) return false;
        }
        return true;
    }

    private static String toHex(final byte[] b, final int max) {
        final int n = Math.min(b.length, max);
        final StringBuilder sb = new StringBuilder(n * 2 + 3);
        for (int i = 0; i < n; i++) {
            sb.append(Character.forDigit((b[i] >> 4) & 0xf, 16)).append(Character.forDigit(b[i] & 0xf, 16));
        }
        if (b.length > max) {
            sb.append("..");
        }
        return sb.toString();
    }

    private static byte[] stripLengthPrefix(final byte[] message) {
        if (message == null || message.length == 0) {
            return null;
        }
        if (message.length >= 4) {
            final int declared = ((message[0] & 0xff) << 24) | ((message[1] & 0xff) << 16)
                    | ((message[2] & 0xff) << 8) | (message[3] & 0xff);
            if (declared == message.length - 4) {
                return java.util.Arrays.copyOfRange(message, 4, message.length);
            }
        }
        return message;
    }

    /**
     * Navigates envelope field 1 (payload) → payload field 7 (NodeInfo) and returns the NodeInfo
     * submessage bytes, or {@code null} if the message is not a well-formed NodeInfo. The 4-byte
     * big-endian length prefix is stripped if still present.
     */
    private static byte[] extractNodeInfo(final byte[] message) {
        if (message == null || message.length == 0) {
            return null;
        }
        try {
            byte[] envelope = message;
            // Strip the 4-byte length prefix if present (length == remaining byte count).
            if (message.length >= 4) {
                final int declared = ((message[0] & 0xff) << 24) | ((message[1] & 0xff) << 16)
                        | ((message[2] & 0xff) << 8) | (message[3] & 0xff);
                if (declared == message.length - 4) {
                    envelope = java.util.Arrays.copyOfRange(message, 4, message.length);
                }
            }
            // envelope field 1 = payload wrapper
            final byte[] payload = new ProtoReader(envelope).findMessage(FIELD_ENVELOPE_PAYLOAD);
            if (payload == null) {
                return null;
            }
            // payload field 7 = NodeInfo
            return new ProtoReader(payload).findMessage(PAYLOAD_NODE_INFO);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** Prepends the 4-byte big-endian length prefix used to frame every message on the wire. */
    public static byte[] frame(final byte[] message) {
        final byte[] framed = new byte[4 + message.length];
        framed[0] = (byte) (message.length >>> 24);
        framed[1] = (byte) (message.length >>> 16);
        framed[2] = (byte) (message.length >>> 8);
        framed[3] = (byte) message.length;
        System.arraycopy(message, 0, framed, 4, message.length);
        return framed;
    }

    /**
     * Minimal protobuf wire-format writer — just what's needed to build the message shapes above
     * (varint, length-delimited bytes/string, nested sub-messages). Not a general-purpose codec.
     */
    static final class ProtoWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void writeVarint(final int fieldNumber, final long value) {
            writeTag(fieldNumber, 0);
            writeRawVarint(value);
        }

        void writeString(final int fieldNumber, final String value) {
            writeBytes(fieldNumber, value.getBytes(StandardCharsets.UTF_8));
        }

        void writeMessage(final int fieldNumber, final byte[] nested) {
            writeBytes(fieldNumber, nested);
        }

        void writeFixed32(final int fieldNumber, final int value) {
            writeTag(fieldNumber, 5);
            out.write(value & 0xff);
            out.write((value >>> 8) & 0xff);
            out.write((value >>> 16) & 0xff);
            out.write((value >>> 24) & 0xff);
        }

        private void writeBytes(final int fieldNumber, final byte[] value) {
            writeTag(fieldNumber, 2);
            writeRawVarint(value.length);
            out.write(value, 0, value.length);
        }

        private void writeTag(final int fieldNumber, final int wireType) {
            writeRawVarint(((long) fieldNumber << 3) | wireType);
        }

        private void writeRawVarint(long value) {
            while (true) {
                if ((value & ~0x7FL) == 0) {
                    out.write((int) value);
                    return;
                }
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    /**
     * Minimal protobuf wire-format reader - just enough to walk one message level and pull out a
     * length-delimited field (sub-message or string) by field number. Not a general-purpose codec.
     */
    static final class ProtoReader {
        private final byte[] buf;
        private int pos;
        private final int end;

        ProtoReader(final byte[] buf) {
            this.buf = buf;
            this.pos = 0;
            this.end = buf.length;
        }

        /** Returns the bytes of the first length-delimited field with the given number, or null. */
        byte[] findMessage(final int fieldNumber) {
            final byte[] bytes = findLenField(fieldNumber);
            return bytes;
        }

        /** Returns the bytes of every length-delimited field with the given number (repeated). */
        java.util.List<byte[]> findAllMessages(final int fieldNumber) {
            final java.util.List<byte[]> out = new java.util.ArrayList<>();
            pos = 0;
            while (pos < end) {
                final long tag = readRawVarint();
                final int field = (int) (tag >>> 3);
                final int wireType = (int) (tag & 0x7);
                if (wireType == 2) {
                    final int len = (int) readRawVarint();
                    if (len < 0 || pos + len > end) {
                        break;
                    }
                    if (field == fieldNumber) {
                        out.add(java.util.Arrays.copyOfRange(buf, pos, pos + len));
                    }
                    pos += len;
                } else if (wireType == 0) {
                    readRawVarint();
                } else if (wireType == 1) {
                    pos += 8;
                } else if (wireType == 5) {
                    pos += 4;
                } else {
                    break;
                }
            }
            return out;
        }

        /** Returns the first length-delimited field with the given number decoded as UTF-8, or null. */
        String findString(final int fieldNumber) {
            final byte[] bytes = findLenField(fieldNumber);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }

        /** Returns the first varint field with the given number, or null. */
        Long findVarint(final int fieldNumber) {
            pos = 0;
            while (pos < end) {
                final long tag = readRawVarint();
                final int field = (int) (tag >>> 3);
                final int wireType = (int) (tag & 0x7);
                if (wireType == 0) {
                    final long v = readRawVarint();
                    if (field == fieldNumber) {
                        return v;
                    }
                } else if (wireType == 2) {
                    final int len = (int) readRawVarint();
                    if (len < 0 || pos + len > end) {
                        return null;
                    }
                    pos += len;
                } else if (wireType == 1) {
                    pos += 8;
                } else if (wireType == 5) {
                    pos += 4;
                } else {
                    return null;
                }
            }
            return null;
        }

        private byte[] findLenField(final int fieldNumber) {
            pos = 0;
            while (pos < end) {
                final long tag = readRawVarint();
                final int field = (int) (tag >>> 3);
                final int wireType = (int) (tag & 0x7);
                switch (wireType) {
                    case 0: // varint
                        readRawVarint();
                        break;
                    case 2: { // length-delimited
                        final int len = (int) readRawVarint();
                        if (len < 0 || pos + len > end) {
                            return null;
                        }
                        if (field == fieldNumber) {
                            return java.util.Arrays.copyOfRange(buf, pos, pos + len);
                        }
                        pos += len;
                        break;
                    }
                    case 1: // 64-bit
                        pos += 8;
                        break;
                    case 5: // 32-bit
                        pos += 4;
                        break;
                    default:
                        return null; // unknown/unsupported wire type - give up
                }
            }
            return null;
        }

        private long readRawVarint() {
            long result = 0;
            int shift = 0;
            while (pos < end) {
                final byte b = buf[pos++];
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift >= 64) {
                    break;
                }
            }
            return result;
        }
    }
}
