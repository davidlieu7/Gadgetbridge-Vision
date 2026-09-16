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
import java.security.SecureRandom;
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

    private static final int PAYLOAD_NODE_INFO = 7;
    private static final int PAYLOAD_SEQ_ACK = 8;
    private static final int PAYLOAD_DATA_ITEM = 9;

    private static final int SEQACK_SENTINEL = 1;
    private static final int SEQACK_NODE_REF = 2;
    private static final int SEQACK_FLAG = 3;
    private static final int NODE_REF_ID = 1;
    private static final int NODE_REF_COUNTER = 2;

    // NodeInfo fields
    private static final int NODE_ID = 1;
    private static final int NODE_NAME = 2;
    private static final int NODE_TYPE = 4;
    private static final int NODE_ROLE = 5;
    private static final int NODE_UNKNOWN6 = 6;
    private static final int NODE_SDK_LEVEL = 11;

    // DataItem fields
    private static final int DATAITEM_SOURCE_PACKAGE = 1;
    private static final int DATAITEM_PATH = 2;
    private static final int DATAITEM_PAYLOAD = 4;
    private static final int DATAITEM_SEQ = 5;
    private static final int DATAITEM_DELETE_FLAG = 6;
    private static final int DATAITEM_DEST_NODE_ID = 7;

    // DataMap entry fields (a repeated {key, value} list, itself protobuf-encoded)
    private static final int DATAMAP_ENTRY = 1;
    private static final int DATAMAP_ENTRY_KEY = 1;
    private static final int DATAMAP_ENTRY_VALUE = 2;
    private static final int DATAMAP_VALUE_TYPE_TAG = 1;
    private static final int DATAMAP_VALUE_TYPE_STRING = 2;
    private static final int DATAMAP_VALUE_TYPE_BOOL = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WearDataLayerProtocol() {
    }

    /**
     * Builds the initial handshake message a client is expected to send right after the RFCOMM
     * socket connects, announcing itself as a Wear Data Layer node. Modeled on the phone-side
     * NodeInfo message captured from the stock Galaxy Wearable app; field 7 (this node's own
     * numeric id, a large integer of unclear meaning on the real app) and field 6 are omitted
     * here since their purpose wasn't determined.
     *
     * @param localNodeId  an 8-hex-char id identifying this "phone" node, same shape as the ids
     *                     seen on the wire (e.g. derived from a random/stable per-install value)
     * @param localName    a human-readable name for this node, shown as the connected device name
     *                     in some of the watch's own UI
     */
    public static byte[] buildNodeInfoHandshake(final String localNodeId, final String localName) {
        final ProtoWriter nodeInfo = new ProtoWriter();
        nodeInfo.writeString(NODE_ID, localNodeId);
        nodeInfo.writeString(NODE_NAME, localName);
        nodeInfo.writeVarint(NODE_TYPE, 3);
        nodeInfo.writeVarint(NODE_ROLE, 1); // 1 = phone/initiator role, per captured traffic
        nodeInfo.writeVarint(NODE_UNKNOWN6, 0);
        nodeInfo.writeVarint(NODE_SDK_LEVEL, Build.VERSION.SDK_INT);

        final ProtoWriter payload = new ProtoWriter();
        payload.writeMessage(PAYLOAD_NODE_INFO, nodeInfo.toByteArray());

        return frame(wrapEnvelope(payload.toByteArray()));
    }

    /**
     * Builds a DataItem PUT for the simple, generic boolean-setting mechanism confirmed live
     * against a real Watch7: writing a single-key DataMap {@code {"booleanIsChecked": value}} to
     * a {@code w_module_sec_watch_setting/...} (or similar) path is enough to change most simple
     * on/off watch settings — see the settings path catalog in the project's research notes for
     * concrete paths (e.g. {@code /w_module_sec_watch_setting/display/always_on_display}).
     *
     * @param sourcePackage this app's own applicationId (used as the DataItem's source package)
     * @param destNodeId    the target watch's node id (learned from its own NodeInfo handshake)
     * @param settingsPath  the settings path, WITHOUT the {@code wear://<nodeId>} prefix, e.g.
     *                      {@code "/w_module_sec_watch_setting/display/always_on_display"}
     * @param value         the boolean value to set
     * @param seq           a monotonically increasing per-path sequence number
     */
    public static byte[] buildBooleanSettingPut(final String sourcePackage, final String destNodeId,
                                                 final String settingsPath, final boolean value,
                                                 final long seq) {
        final byte[] dataMap = buildSingleKeyBooleanDataMap("booleanIsChecked", value);

        final ProtoWriter dataItem = new ProtoWriter();
        dataItem.writeString(DATAITEM_SOURCE_PACKAGE, sourcePackage);
        dataItem.writeString(DATAITEM_PATH, "wear://" + destNodeId + settingsPath);
        dataItem.writeMessage(DATAITEM_PAYLOAD, dataMap);
        dataItem.writeVarint(DATAITEM_SEQ, seq);
        dataItem.writeVarint(DATAITEM_DELETE_FLAG, 0);
        dataItem.writeString(DATAITEM_DEST_NODE_ID, destNodeId);

        final ProtoWriter payload = new ProtoWriter();
        payload.writeMessage(PAYLOAD_DATA_ITEM, dataItem.toByteArray());

        return frame(wrapEnvelope(payload.toByteArray()));
    }

    /**
     * Builds the "SeqAck/Ping" message observed sent early (and periodically) in real
     * captured sessions, shortly after the NodeInfo handshake — a plausible candidate for
     * "why does the watch close the channel ~100ms after we send NodeInfo and it replies":
     * it may expect this as a prompt follow-up and time out otherwise.
     *
     * @param remoteNodeId the OTHER side's node id (learned from its NodeInfo reply)
     * @param counter      a small counter value, meaning not fully confirmed
     */
    public static byte[] buildSeqAck(final String remoteNodeId, final long counter) {
        final ProtoWriter nodeRef = new ProtoWriter();
        nodeRef.writeString(NODE_REF_ID, remoteNodeId);
        nodeRef.writeVarint(NODE_REF_COUNTER, counter);

        final ProtoWriter seqAck = new ProtoWriter();
        seqAck.writeVarint(SEQACK_SENTINEL, -1L); // 0xFFFFFFFFFFFFFFFF as seen on the wire
        seqAck.writeMessage(SEQACK_NODE_REF, nodeRef.toByteArray());
        seqAck.writeVarint(SEQACK_FLAG, 2);

        final ProtoWriter payload = new ProtoWriter();
        payload.writeMessage(PAYLOAD_SEQ_ACK, seqAck.toByteArray());

        return frame(wrapEnvelope(payload.toByteArray()));
    }

    private static byte[] buildSingleKeyBooleanDataMap(final String key, final boolean value) {
        final ProtoWriter valueWrapper = new ProtoWriter();
        valueWrapper.writeVarint(DATAMAP_VALUE_TYPE_TAG, DATAMAP_VALUE_TYPE_BOOL);
        valueWrapper.writeVarint(DATAMAP_VALUE_TYPE_BOOL, value ? 1 : 0);

        final ProtoWriter entry = new ProtoWriter();
        entry.writeString(DATAMAP_ENTRY_KEY, key);
        entry.writeMessage(DATAMAP_ENTRY_VALUE, valueWrapper.toByteArray());

        final ProtoWriter dataMap = new ProtoWriter();
        dataMap.writeMessage(DATAMAP_ENTRY, entry.toByteArray());
        return dataMap.toByteArray();
    }

    private static byte[] wrapEnvelope(final byte[] payload) {
        final ProtoWriter envelope = new ProtoWriter();
        envelope.writeMessage(FIELD_ENVELOPE_PAYLOAD, payload);
        envelope.writeString(FIELD_ENVELOPE_REQUEST_ID, randomRequestId());
        envelope.writeVarint(FIELD_ENVELOPE_FLAG_A, 1);
        envelope.writeVarint(FIELD_ENVELOPE_FLAG_B, 1);
        return envelope.toByteArray();
    }

    /**
     * Request ids observed on the wire look like ~27-character URL-safe base64 strings (no
     * padding) — matches a base64url-encoded 20-byte random value.
     */
    private static String randomRequestId() {
        final byte[] raw = new byte[20];
        RANDOM.nextBytes(raw);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
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
}
