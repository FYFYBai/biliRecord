# biliRecord

A private Bilibili live monitor and recorder. It monitors room status, resolves
live streams and can automatically record a complete live session with its
danmaku timeline.

## Requirements

- Java 21 or newer
- Maven 3.9 or newer
- FFmpeg and FFprobe 8 or newer (required from the recorder phase)

## Build

```shell
mvn clean package
```

Build output stays under the locally ignored `target/` directory.

## Usage

Check once with a room ID or a full Bilibili Live URL:

```shell
java -jar target/bili-record.jar 6
java -jar target/bili-record.jar https://live.bilibili.com/6
```

Monitor continuously with a randomized 25-35 second polling interval:

```shell
java -jar target/bili-record.jar 6 --watch
```

List the room owner UID, quality descriptions, protocols, formats, codecs,
reported dimensions and CDN candidates:

```shell
java -jar target/bili-record.jar 92613 --streams
```

Signed stream URLs are retained by the resolver for the recorder phase, but the
CLI only prints CDN hostnames. API quality numbers are treated as platform
quality tiers; reported dimensions will be independently checked with FFprobe
in the recorder phase.

## Authentication

The production authentication flow uses Bilibili QR login. It never asks for a
password and does not extract cookies from an existing browser profile.

```shell
java -jar target/bili-record.jar --login
```

The login window displays a QR code to scan and confirm with the Bilibili app.
Cookies and the refresh token are saved only to the ignored local file
`data/auth/cookies.json`. Authenticated API requests load that file without
printing its contents. A future application UI will reuse this `AuthManager`
flow as a login dialog and expose only login state, account identity and a
logout command.

The planned credential lifecycle is:

```text
QR login -> local auth file -> validity check -> refresh or re-login -> logout
```

## Record a sample

Resolve the stream closest to 1080p, confirm its actual dimensions with
FFprobe, then remux it to MKV without re-encoding:

```shell
java -jar target/bili-record.jar 92613 --record 30
```

Recordings stay under the locally ignored `recordings/` directory. This phase
records a requested duration.

## Automatic recording

Keep monitoring a room, start video and danmaku capture when it goes live, then
stop after three consecutive offline confirmations five seconds apart:

```shell
java -jar target/bili-record.jar 92613 --auto
```

An automatic run writes one session directory containing `video/000000.mkv`,
`logs/ffmpeg.log`, `timeline.sqlite` and `raw-events.jsonl`. Press Ctrl+C to
finish the active session cleanly. Network and process recovery will be added
in Phase 8.

## Listen to danmaku

Connect to the live-room WebSocket, authenticate, send heartbeats and print
`DANMU_MSG` events for a requested duration:

```shell
java -jar target/bili-record.jar 92613 --danmaku 30
```

The binary decoder supports normal packets, nested packets, zlib and Brotli.
Other event packets are persisted to JSONL but are not printed by the CLI.

Each danmaku run now creates a local session directory:

```text
recordings/room_<room-id>/<timestamp>/
├── timeline.sqlite
└── raw-events.jsonl
```

Every decoded server event is written to JSONL with its receive timestamp,
video-relative `sessionOffsetMs` and a server timestamp when the event provides
one. SQLite stores the same timeline fields, session and video anchors,
recording segments, and parsed `DANMU_MSG` fields for later search and playback
seeking.

The one-shot command prints `LIVE` when `live_status` is `1`; other room states
print `OFFLINE`.

## Status

- [x] Phase 1: room monitor
- [x] Phase 2: stream resolver
- [x] Authentication prerequisite: QR login and local storage
- [x] Phase 3: recorder
- [x] Phase 4: danmaku client
- [x] Phase 5: storage
- [x] Phase 6: session clock
- [x] Phase 7: lifecycle
- [ ] Phase 8: recovery

Authentication data and recordings are local-only and must not be committed.
