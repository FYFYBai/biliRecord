# biliRecord

A private Bilibili live monitor and recorder with a Windows desktop UI. It
monitors room status, resolves live streams and automatically records a complete
live session with its event timeline.

## Requirements

- Java 21 or newer
- Maven 3.9 or newer
- FFmpeg and FFprobe 8 or newer (required from the recorder phase)
- VLC 3.x, 64-bit (required for embedded playback)
- Python 3.9 or newer (optional, required only for local speech recognition)

## Build

```shell
mvn clean package
```

Build output stays under the locally ignored `target/` directory.

## Usage

Open the desktop application by launching the JAR without arguments:

```shell
java -jar target/bili-record.jar
```

The UI accepts a room number or full Bilibili Live URL. It shows the canonical
room ID, anchor UID, title, live state, elapsed recording time, session size and
free disk space. QR login and account switching are available from the top bar.
The selected room and most recently chosen export directory are remembered in
the ignored local file `data/settings.json`.

The **录制记录** tab opens a V2.1 review window for completed local sessions.
It plays all MKV segments as one session timeline, preserves gaps caused by a
real reconnect, and provides playback, seeking, volume and mute controls.
Double-clicking a timeline row seeks to the matching moment. Search covers the
visible normalized events, users, message text, gifts and speech transcript;
the type selector narrows the same combined timeline.

Select **导出片段** to open a separate time-range form. Start and end values
can be entered as hours, minutes and seconds or copied from the current player
position. The app confirms the range and destination before exporting MP4,
MKV or WebM; recording gaps inside the selection are omitted from the output.

The review window can generate a local transcript with `faster-whisper`.
Choose a model, CPU or CUDA, and a language, then select **生成转录**. On first
use the app creates a private Python environment and downloads the chosen model
under the ignored `data/asr/` directory. Audio, transcript rows and searchable
text stay on this machine. Transcript rows are written to the session's
`timeline.sqlite` and `transcript.jsonl`; regenerating replaces only those
transcript rows.

Closing or minimizing the window sends it to the Windows system tray. Native
notifications report live start/stop, successful recovery, low disk space and
runtime errors. Fatal errors are also shown in a dialog when the window is
visible. Rotating application logs stay under the ignored `logs/` directory;
each session keeps separate FFmpeg logs.

The remaining commands provide direct diagnostics and testing.

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
printing its contents. The desktop top bar opens the same flow for login and
account switching while exposing only the local login state.

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

An automatic run writes one session directory containing video segments,
FFmpeg process logs, `timeline.sqlite` and `raw-events.jsonl`. Press Ctrl+C to
finish the active session cleanly.

Temporary failures are recovered without ending the live session. Room API
requests use a capped `1s, 2s, 5s, 10s, 30s` backoff. A closed danmaku socket
gets a fresh host and token; an exited FFmpeg process gets a newly resolved
stream URL and starts another timeline-aligned MKV segment. All CDN candidates
for the selected stream variant are tried before the next backoff cycle.
For a healthy recording, one continuous FFmpeg process uses the segment muxer
to start a new MKV at a keyframe roughly every 30 minutes. The input stream is
not reconnected during a scheduled split, so the boundary does not intentionally
drop media. A stalled stream still starts a fresh FFmpeg process and stream URL.

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
recording segments, and normalized display events for later search and playback
seeking. The desktop feed displays only `LIVE`, `PREPARING`, `DANMU_MSG`,
`ROOM_CHANGE`, `SEND_GIFT`, `SUPER_CHAT_MESSAGE` and `GUARD_BUY`. Other commands
remain available in raw JSONL but are hidden from the UI.

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
- [x] Phase 8: recovery
- [x] Phase 9: desktop UI and normalized event feed
- [x] V2: embedded local playback, unified timeline and search-to-seek
- [x] V2.1: local faster-whisper transcription and transcript search

Authentication data, recordings, speech models, transcripts, test sources and
build outputs are local-only and must not be committed.
