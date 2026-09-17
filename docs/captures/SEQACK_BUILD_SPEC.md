# Galaxy Watch7 Data Layer — NodeInfo parse + SeqAck/peer-sync build spec

Implementation-ready, byte-level spec for surviving the RFCOMM Data Layer
handshake. Derived entirely from the reference capture
`watch7plugin_datalayer_messages.txt` / `watch7plugin_datalayer_session.btsnoop`.
Every structural claim below is verified against exact bytes and message
lengths (P1 decodes to exactly 175 bytes, W1 to exactly 130 bytes).

Transport: RFCOMM, UUID `5e8945b0-9525-11e3-a5e2-0800200c9a66`, DLCI 0x18
(channel 12). Framing: `[4-byte big-endian length][protobuf envelope]`, one
envelope per logical message (Android BT stack reassembles RFCOMM fragments).

Node ids in the reference session (they ROTATE per install/reboot — never
hardcode): **phone = `e1b1cd8b` ("Mi 10T")**, **watch = `1bce196f`
("Galaxy Watch7 (CLMP)")**. GB's old hardcoded `19eaaf8` is a *historical*
node id and is wrong for a live session.

---

## 0. Envelope model (corrected)

The envelope is a protobuf message. Field 1 is a payload sub-message whose
inner field number selects the type (oneof-style): 7=NodeInfo, 8=SeqAck/peer-sync,
9=DataItem, 10=MessageEvent. **The request-id and the trailing 1/1/3 flags are
OUTER envelope fields, not part of field 8** (this corrects the earlier guess in
NOTES §14).

```
Envelope
  1  payload      (message)   inner field 7|8|9|10 = type
  2  requestId    (string)    27 chars, random base64url  (per-message)
  3  flag         (varint)    always 1
  4  flag         (varint)    always 1
  5  version      (varint)    3   -- PRESENT on peer-sync & DataItem;
                                     ABSENT on NodeInfo messages
```

---

## 1. NodeInfo reply parse — read the watch's live node id

Reference WATCH->PHONE message [0] (W0), full frame payload
(`len=108`):

```
0a49 3a47 0a08 3162636531393666 1214 47616c617879205761746368372028434c4d5029
18 ffb6b6d7f0e8a9b838 20 03 28 02 30 00 3a13 34393335373736393934343736303434393833
58 24 12 1b 6a4f68576b74483662637044304e794945415f6f34507a6753533018012001
```

Annotated:

```
0a 49                       envelope field 1 (payload), len 0x49=73
  3a 47                     inner field 7 = NodeInfo, len 0x47=71
    0a 08 3162636531393666  NodeInfo.field1 = nodeId (string,8) = "1bce196f"  <== WATCH CURRENT NODE ID
    12 14 47616c...4d5029   NodeInfo.field2 = name (string,20) = "Galaxy Watch7 (CLMP)"
    18 ffb6b6d7f0e8a9b838   NodeInfo.field3 = varint (big; capability/opaque)
    20 03                   NodeInfo.field4 = varint 3   (node type, constant 3)
    28 02                   NodeInfo.field5 = varint 2   (role: 2=watch, phone sends 1)
    30 00                   NodeInfo.field6 = varint 0
    3a 13 3439333537...     NodeInfo.field7 = string(19) "4935776994476044983" (shared GAIA/account id)
    58 24                   NodeInfo.field11 = varint 0x24=36 (SDK level)
12 1b 6a4f68...5330         envelope field 2 = requestId(27) "jOhWktH6bcpD0NyIEA_o4PzgSS0"
18 01                       envelope field 3 = 1
20 01                       envelope field 4 = 1
                            (no field 5 on NodeInfo)
```

### Parse recipe (drop the hardcoded id)

1. Read outer field 1 (tag `0x0a`, wire type 2). Confirm the first inner tag is
   `0x3a` (field 7, wire type 2) => this is a NodeInfo payload.
2. Inside NodeInfo, read field 1 (tag `0x0a`, wire type 2): a length-prefixed
   **UTF-8/ASCII string** — that is the watch's **current node id** (e.g.
   `1bce196f`). This is the value GB must use everywhere it currently uses
   `EXPERIMENT_WATCH_NODE_ID`.
3. Optional but useful: field 2 = display name, field 5 = role (2=watch),
   field 11 = SDK level.

Contrast — the PHONE's own NodeInfo (P0, message [0] PHONE->WATCH) has the
identical layout with field1=`e1b1cd8b`, field2="Mi 10T", field5=**1** (phone
role). GB already sends this shape; keep it, but make sure GB's own field1 is
its real generated node id and field5=1.

---

## 2. SeqAck / peer-sync (payload field 8) — build spec

### 2.1 What the message is

A **vector-clock / cumulative sync-ack**: for every node the sender knows, it
reports the highest sequence number it has consumed from that node. The watch
uses it to decide what state to (re)send. It is sent once, right after
NodeInfo, in both directions.

### 2.2 Verified structure

Reference PHONE->WATCH message [1] (P1), `len=175`, decodes byte-exactly:

```
0a 89 01                                envelope field 1 (payload), len 137
  42 86 01                              inner field 8 = SeqAck, len 134
    08 c0 0e                            field8.field1 = varint 1856   (see 2.3)
    12 0a 0a05 636c6f7564       10 cb14 entry {"cloud",    2635}
    12 0b 0a06 613938306234     10 dd04 entry {"a980b4",    605}
    12 0d 0a08 6566623363313835 10 f808 entry {"efb3c185", 1144}
    12 0d 0a08 6531623163643862 10 9f4e entry {"e1b1cd8b",10015}   (phone/self)
    12 0d 0a08 3665363461363232 10 f306 entry {"6e64a622",  883}
    12 0d 0a08 3864653061303935 10 ca09 entry {"8de0a095", 1226}
    12 0d 0a08 3162636531393666 10 c00e entry {"1bce196f", 1856}   (watch/dest)
    12 0d 0a08 6233356233356436 10 dd0b entry {"b35b35d6", 1501}
    12 0c 0a07 3139656161663810 a019    entry {"19eaaf8",  3232}   (note: 7-char id)
    18 02                               field8.field3 = varint 2   (constant)
12 1b 396a6f70...7a36334d               envelope field 2 = requestId(27) "9jopUVBaTzcGrzNHO_5dbeWz63M"
18 01                                   envelope field 3 = 1
20 01                                   envelope field 4 = 1
28 03                                   envelope field 5 = 3
```

Byte accounting: field8 content = field1(3) + 9 entries(129) + field3(2) = 134;
outer field1 = tag/len(3)+134 = 137; + reqid(29) + 1/1/3(6) = **175**. Confirmed.

W1 (WATCH->PHONE [1], `len=130`) has the identical shape with 6 entries and
reqid "y9arSzyQevR-nQiaLzA_jDtIqGg"; it also decodes to exactly 130 bytes.

### 2.3 field8.field1 — SOLVED: it is the recipient node's sequence counter, NOT a magic

Proof from the two captured directions:

| message | direction | field8.field1 | entry that equals it | that entry's node is |
|---------|-----------|---------------|----------------------|----------------------|
| P1      | phone->watch | 1856 (`c0 0e`) | `1bce196f` = 1856 | the WATCH (recipient) |
| W1      | watch->phone | 10014 (`9e 4e`)| `e1b1cd8b` = 10014| the PHONE (recipient) |

In each direction field8.field1 exactly equals the map counter for the *other*
endpoint (the recipient), and matches no other entry. So:

> **field8.field1 = "the sequence number I (sender) have for YOU (recipient)"**
> — a redundant, explicit SeqAck of the recipient's own node counter.

It is **not** a constant, a session id, or a total. (NOTES §14's `20126/20127`
were a varint mis-read: the two bytes `9e 4e` / `9f 4e` are LEB128 = 10014 /
10015, not 0x4e9e / 0x4e9f.)

**For a fresh GB session: set field8.field1 = 0.** GB has consumed nothing from
the watch, so 0 is the honest, correct value; it also asks the watch to resend
full state (exactly what GB wants). **Safe fallback if 0 is ever rejected:**
echo the counter the watch advertises for GB's own node in its W1 (i.e. ack up
to what the watch just told you). But 0 is the semantically correct starting
value and is what a never-before-seen node legitimately reports.

### 2.4 Which peers are required — minimal set

The two captured maps are **asymmetric**: P1 lists 9 nodes; W1 lists only 6 and
**omits** `a980b4`, `8de0a095`, `b35b35d6`. Since each side simply lists the
nodes *it* knows and the watch still accepted P1, the watch does **not** validate
against any fixed/required node set, and **historical ids are NOT required**.

**Minimum viable set for a fresh GB session (recommended):**

```
{ "cloud",     0 }     # virtual cloud-sync node; present in BOTH captured maps -> include it
{ <ownNodeId>, 0 }     # GB's own node id (same string GB puts in its NodeInfo field1)
{ <watchNodeId>, 0 }   # the id parsed live from the watch's NodeInfo (§1), e.g. "1bce196f"
```

- `<watchNodeId>` (the current watch node) — **required**: this is how the phone
  acks the watch's stream; must match the live NodeInfo value, never the old
  `19eaaf8`.
- `<ownNodeId>` — **required**: tells the watch GB's sync position.
- `cloud` — **strongly recommended**: it appears in every captured map, so it is
  a well-known node the watch expects; including it with counter 0 is zero-risk.

Do not include historical ids GB never knew.

### 2.5 Per-entry counter — meaning and fresh value

Each entry is `{ field1 = nodeId (string), field2 = counter (varint) }`. The
counter is the **highest DataItem sequence number the sender has received from
that node** (a per-node cumulative "last-seen sequence"). Evidence: across the
two maps the same node's counter differs by ~1 between sides (e.g. `1bce196f` =
1856 in P1 vs 1857 in W1; `e1b1cd8b` = 10015 in P1 vs 10014 in W1) — i.e. one
in-flight item apart, exactly what a last-seen sync counter looks like.

**For a fresh GB session use 0 for every entry** — GB has seen nothing yet, so
the watch will (re)send the full current state.

### 2.6 requestId and trailing flags — exact placement

- `requestId` is **envelope field 2** (tag `0x12`), a 27-char random base64url
  string. It sits AFTER the payload (field 1), not inside field 8. Generate a
  fresh one per message (26–27 chars observed; use 27).
- Envelope trailing flags, in order, AFTER field 2:
  `field 3 = 1` (`18 01`), `field 4 = 1` (`20 01`), `field 5 = 3` (`28 03`).
- field 8's OWN trailing flag is `field 3 = 2` (`18 02`), placed at the end of
  the field-8 sub-message, before the envelope-level fields. Constant 2.

---

## 3. Fully worked GB peer-sync (annotated hex)

Fresh session, minimal peer set `{cloud:0, ownNode:0, watchNode:0}`, using a
sample own node id `gbnode01`, the live watch id `1bce196f`, and a sample
27-char requestId `Gb0000000000000000000000000`. Substitute GB's real own node
id, the parsed watch id, and a real random requestId.

Complete frame (4-byte length prefix + envelope), 82-byte envelope:

```
00 00 00 52                          <- 4-byte BE length prefix = 0x52 = 82
0a 2d                                envelope field 1 (payload), len 45
  42 2b                              inner field 8 = SeqAck, len 43
    08 00                            field8.field1 = 0        (recipient seq, fresh)
    12 09 0a 05 636c6f7564   10 00   entry {"cloud",    0}
    12 0c 0a 08 67626e6f6465 3031 10 00   entry {"gbnode01",0}
    12 0c 0a 08 3162636531393666 10 00    entry {"1bce196f",0}   (live watch id)
    18 02                            field8.field3 = 2       (constant)
12 1b 4762303030...3030               envelope field 2 = requestId(27) "Gb0000000000000000000000000"
18 01                                envelope field 3 = 1
20 01                                envelope field 4 = 1
28 03                                envelope field 5 = 3
```

Contiguous hex (copy/paste testable):

```
000000520a2d422b080012090a05636c6f75641000120c0a0867626e6f646530311000120c0a08316263653139366610001802121b476230303030303030303030303030303030303030303030303030180120012803
```

Node-ref entry construction rule (per peer): let `id` = ASCII node-id bytes,
`n = len(id)`.
```
inner = 0x0a, n, id..., 0x10, <counter varint>          # {field1=id, field2=counter}
entry = 0x12, len(inner), inner...
```
field8 = `0x08,<field1 varint>` + concat(entries) + `0x18,0x02`.
payload(field1) = `0x0a,len(field8_with_tag)` wrapping `0x42,len(field8),field8`.
Then append `0x12,27,<reqid>` + `18 01 20 01 28 03`.
(For counters/lengths >127 emit multi-byte LEB128 varints; a fresh session's
0-counters and small maps stay single-byte.)

---

## 4. Handshake order and keep-alive requirement

### 4.1 Exact opening order (from btsnoop frame timing, DLCI 0x18)

```
t=2.243  SABM (phone) / t=2.263 UA (watch)      channel opens
t=2.278  phone -> NodeInfo   (P0, 94+4 bytes)
t=2.326  watch -> NodeInfo   (W0, 108+4)
t=2.338  watch -> peer-sync  (W1, 130+4)
t=2.345  phone -> peer-sync  (P1, 175+4)
t=2.364+ phone -> DataItem PUT stream ...
```

So: **NodeInfo first (both), then peer-sync (both), then DataItems.** GB should
send its NodeInfo, wait for the watch's NodeInfo, parse the watch id, then send
the peer-sync built per §2/§3.

### 4.2 Keep-alive — NO periodic ping needed to survive past ~1s

Measured on the reference session (DLCI 0x18):

- Session lifetime: SABM 2.243s -> DISC 14.460s (~12.2s).
- **Largest gap with zero application data in EITHER direction = 8.796s**
  (from ~2.95s to 11.75s). The watch did **not** disconnect during this 8.8s of
  complete silence.
- The final **DISC was sent by the PHONE** (`ff:dd:b7:2f:9f:04`), i.e. a
  graceful client-side close (app/capture ending) — **not** a watch-side idle
  timeout or drop.
- Over the whole session the watch sent only **4** application messages
  (NodeInfo, peer-sync, 2 DataItem PUTs); everything else watch->phone was
  zero-length RFCOMM credit frames (flow control handled by the Android BT
  stack, not the app).

**Conclusion:** the ~100 ms drop GB currently suffers is caused solely by the
malformed peer-sync, NOT by a missing heartbeat. Once GB sends a valid NodeInfo
+ a valid peer-sync (§2), the watch establishes the sync session and holds the
socket open through long silence. To survive past ~1s GB does **not** need to:
(a) app-level ack the watch's DataItems, (b) send its own DataItem, or (c) ping.

**Minimum to keep the session up past ~1s:**
1. Send a well-formed NodeInfo (GB's real node id, role field5=1).
2. Parse the watch's NodeInfo -> live watch node id.
3. Send a well-formed peer-sync per §2/§3 (field8.field1=0, entries
   {cloud:0, ownNode:0, watchNodeLive:0}, field8.field3=2, reqid, 1/1/3).
4. Keep reading (and not crashing on) the watch's incoming frames; let the
   Android stack handle RFCOMM credit flow control.

### 4.3 Recommended (beyond bare survival)

For correct ongoing sync — not required for the 1s survival test but expected
for real settings/notification exchange:
- Track the sequence numbers on incoming watch DataItems (envelope field 9,
  field 5 = per-path sequence) and reflect the highest-seen values back in
  later peer-sync messages (bump the watch-node counter and field8.field1
  accordingly). For the initial handshake, 0 is correct.
- The reference session showed no idle timeout up to 8.8s; it cannot rule out a
  longer one (session was only 12s). If a long-lived idle drop is ever observed,
  the safe mitigation is to re-send the peer-sync (or any DataItem) periodically
  — but current evidence says this is unnecessary for normal use.

---

## 5. Quick reference — envelope field tables

NodeInfo (payload field 7):

| field | wire | meaning | phone | watch |
|-------|------|---------|-------|-------|
| 1 | string | nodeId | e1b1cd8b | 1bce196f |
| 2 | string | display name | "Mi 10T" | "Galaxy Watch7 (CLMP)" |
| 3 | varint | opaque/capability (large) | present | present |
| 4 | varint | node type | 3 | 3 |
| 5 | varint | role | 1 | 2 |
| 6 | varint | 0 | 0 | 0 |
| 7 | string | shared GAIA/account id (19 digits) | 4935776994476044983 | same |
| 11| varint | SDK level | 36 | 36 |

SeqAck / peer-sync (payload field 8):

| field | wire | meaning | fresh-GB value |
|-------|------|---------|----------------|
| 1 | varint | recipient node's seq counter (SeqAck) | 0 |
| 2 | repeated msg | node-ref {1=nodeId string, 2=counter varint} | {cloud:0, own:0, watchLive:0} |
| 3 | varint | constant | 2 |

Envelope (all messages):

| field | wire | meaning | value |
|-------|------|---------|-------|
| 1 | msg | payload (7/8/9/10) | per type |
| 2 | string | requestId (27 chars, random) | fresh per message |
| 3 | varint | flag | 1 |
| 4 | varint | flag | 1 |
| 5 | varint | version | 3 (absent on NodeInfo) |
```
