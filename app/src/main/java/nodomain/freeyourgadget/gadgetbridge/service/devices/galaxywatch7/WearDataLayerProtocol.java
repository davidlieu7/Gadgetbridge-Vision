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
    // Present (= 3) on peer-sync and DataItem envelopes, ABSENT on the NodeInfo handshake
    // envelope - confirmed against the reference capture (see research notes §14).
    private static final int FIELD_ENVELOPE_FLAG_C = 5;

    private static final int PAYLOAD_NODE_INFO = 7;
    private static final int PAYLOAD_SEQ_ACK = 8;
    private static final int PAYLOAD_DATA_ITEM = 9;

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

        return frame(wrapEnvelope(payload.toByteArray(), true));
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

    private static void appendNodeRef(final ProtoWriter seqAck, final String nodeId, final long counter) {
        final ProtoWriter nodeRef = new ProtoWriter();
        nodeRef.writeString(NODE_REF_ID, nodeId);
        nodeRef.writeVarint(NODE_REF_COUNTER, counter);
        seqAck.writeMessage(SEQACK_NODE_REF, nodeRef.toByteArray());
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

    private static byte[] wrapEnvelope(final byte[] payload, final boolean withFlagC) {
        final ProtoWriter envelope = new ProtoWriter();
        envelope.writeMessage(FIELD_ENVELOPE_PAYLOAD, payload);
        envelope.writeString(FIELD_ENVELOPE_REQUEST_ID, randomRequestId());
        envelope.writeVarint(FIELD_ENVELOPE_FLAG_A, 1);
        envelope.writeVarint(FIELD_ENVELOPE_FLAG_B, 1);
        if (withFlagC) {
            envelope.writeVarint(FIELD_ENVELOPE_FLAG_C, 3);
        }
        return envelope.toByteArray();
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

        /** Returns the first length-delimited field with the given number decoded as UTF-8, or null. */
        String findString(final int fieldNumber) {
            final byte[] bytes = findLenField(fieldNumber);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
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
