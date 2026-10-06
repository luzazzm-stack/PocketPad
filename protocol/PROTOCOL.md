# PocketPad Wire Protocol — v1

Single source of truth for the Android app and the Windows companion.
Both sides MUST implement exactly this. Any change bumps `PROTOCOL_VERSION`.

- `PROTOCOL_VERSION = 1`
- All multi-byte fields are **little-endian**.
- Default ports: **UDP 46821** (state), **TCP 46822** (control).
- Transports: Wi-Fi/hotspot use UDP+TCP as-is. USB uses the same sockets over
  `adb reverse` loopback. Bluetooth RFCOMM carries the same frames over its
  stream (each frame prefixed by a `u8` length byte).

## State packet — phone → PC, 16 bytes, sent at 125 Hz

Sent every 8 ms while connected. UDP: one packet per datagram. Receiver drops
any packet whose `seq` is older than the newest seen (accounting for u16
wraparound).

**Senders MUST hold every button and d-pad change for at least 24 ms** (three
packets) before that same control may change again, queueing any faster input
rather than dropping or merging it. A press carried by a single packet is
shorter than a 60 Hz game's input frame, so the game can poll straight past
it, and one lost datagram erases it outright — a sender that just samples its
latest state per tick silently drops fast taps and turns three taps into one.
Controls pace independently: a d-pad roll must never delay a button.
The same rule applies to the trackpad packet's buttons.

| Offset | Size | Field    | Notes |
|--------|------|----------|-------|
| 0      | 1    | magic    | `0x50` ('P') |
| 1      | 1    | version  | `0x01` |
| 2      | 2    | seq      | u16, wraps; monotonically increasing |
| 4      | 2    | buttons  | u16 bitmask, see below |
| 6      | 1    | dpad     | u8, see below |
| 7      | 1    | player   | u8 0–3, echoed from `welcome.player` (below) |
| 8      | 2    | lx       | i16, −32768..32767, left stick X |
| 10     | 2    | ly       | i16, left stick Y (up = +32767) |
| 12     | 2    | rx       | i16, right stick X |
| 14     | 2    | ry       | i16, right stick Y (up = +32767) |

Triggers ride in the buttons mask as digital LT/RT for v1 (Tekken needs no
analog triggers). Analog triggers are a v2 field.

### buttons bitmask (u16)

| Bit | Button | Bit | Button |
|-----|--------|-----|--------|
| 0   | A      | 8   | L3     |
| 1   | B      | 9   | R3     |
| 2   | X      | 10  | LT (digital) |
| 3   | Y      | 11  | RT (digital) |
| 4   | LB     | 12–15 | reserved = 0 |
| 5   | RB     |     |        |
| 6   | Back   |     |        |
| 7   | Start  |     |        |

### dpad (u8)

`0`=neutral, `1`=Up, `2`=UpRight, `3`=Right, `4`=DownRight, `5`=Down,
`6`=DownLeft, `7`=Left, `8`=UpLeft. (Clockwise from Up; matches XInput hat.)

## Mouse packet — phone → PC, 12 bytes, same UDP port

Sent instead of the state packet while the phone is in trackpad mode, at the
same 125 Hz. Movement and wheel are **relative deltas that the sender consumes**
(accumulated since the last packet, then zeroed) — never re-send a delta, or the
cursor keeps travelling. Buttons are level-triggered like the pad's.

The receiver dispatches on the magic byte, so both packet types share one socket
and one `seq` space.

| Offset | Size | Field    | Notes |
|--------|------|----------|-------|
| 0      | 1    | magic    | `0x4D` ('M') |
| 1      | 1    | version  | `0x01` |
| 2      | 2    | seq      | u16, shared counter with the state packet |
| 4      | 1    | buttons  | u8: bit0 Left, bit1 Right, bit2 Middle |
| 5      | 1    | player   | u8 0–3, echoed from `welcome.player` |
| 6      | 2    | dx       | i16, relative, +right |
| 8      | 2    | dy       | i16, relative, +down |
| 10     | 1    | wheel    | i8, notches, +up |
| 11     | 1    | reserved | `0x00` |

On mode switch the sender emits one neutral packet of the mode it is *leaving*
(all pad buttons released / all mouse buttons released) so nothing sticks down.

## Control channel — TCP 46822, JSON lines (UTF-8, `\n`-terminated)

| Message | Direction | Shape |
|---------|-----------|-------|
| hello   | phone → PC | `{"t":"hello","v":1,"name":"<device name>","token":"<pairing token>"}` |
| welcome | PC → phone | `{"t":"welcome","v":1,"udp":46821,"player":0}` — token accepted; `player` (0–3) is the slot this phone must stamp into every UDP packet |
| reject  | PC → phone | `{"t":"reject","reason":"token"\|"version"\|"busy"}` then close — `busy` = all 4 player slots taken |
| ping    | phone → PC | `{"t":"ping","id":123,"ts":<phone ms>}` every 1 s |
| pong    | PC → phone | `{"t":"pong","id":123,"ts":<echoed>}` — phone computes RTT for latency meter |
| lat     | phone → PC | `{"t":"lat","ms":4}` — phone reports the RTT it just measured, so the PC UI can show it too. Optional; PC must tolerate its absence |
| bye     | either     | `{"t":"bye"}` graceful disconnect |

Disconnect = TCP close or 5 s without state packets → PC releases that player's
virtual pad.

## Multiplayer

Up to **4 phones** connect to one PC, each getting its own virtual Xbox pad
(player 0–3, lowest free slot assigned at handshake). Sequence numbers are
tracked per player. A phone that predates this field always sends `player=0`,
which is only correct for the first phone — multiplayer needs current apps on
both ends.

## Pairing QR code

PC app displays a QR encoding: `pocketpad://pair?host=<ip>&tcp=46822&token=<8-char token>`
The token is shown as text beside the QR for manual entry. The ship app creates
it once and keeps it (`%LOCALAPPDATA%PocketPadpairing-code.txt`), so a paired
phone stays paired across PC restarts; dev tools may still use a per-launch one.

## Discovery — UDP 46821, 8 bytes

Lets a paired phone find its PC again after the PC's address changes (new DHCP
lease, other network). The phone sends a **request** to `255.255.255.255` and
each interface's subnet broadcast on the state port; the PC holding the
matching token answers with a **reply** to the sender's address and port. The
phone dials the address the reply came from. The token never goes on the wire,
only its fingerprint, and a PC stays silent for any other fingerprint.

| Offset | Size | Field       | Notes |
|--------|------|-------------|-------|
| 0      | 1    | magic       | `0x44` ('D') |
| 1      | 1    | version     | `0x01` |
| 2      | 1    | kind        | `0` = request (phone → broadcast), `1` = reply (PC → phone) |
| 3      | 1    | reserved    | `0x00` |
| 4      | 4    | fingerprint | first 4 bytes of SHA-256(UTF-8 token) |

Phones send discovery only after a connect to the saved address fails, re-send
every 300 ms, and give up after 2.5 s. A PC that predates this section simply
ignores the unknown magic byte.
