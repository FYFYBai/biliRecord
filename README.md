# biliRecord

[简体中文](README_CN.md)

A private Bilibili live monitor, recorder and review tool with a Windows desktop
UI. It monitors room status, automatically records complete live sessions,
captures a searchable event timeline, plays local recordings and generates
local speech transcripts.

## Download

Download the self-contained Windows installer from [GitHub Releases](https://github.com/FYFYBai/biliRecord/releases).
It includes the Java runtime, FFmpeg/FFprobe, VLC, Python and faster-whisper, so
users do not need to install or configure a development environment. The first
transcription still downloads the selected speech model and therefore requires
an internet connection.

## Desktop features

- Monitor a room number or Bilibili Live URL and remember the selected room.
- Log in or switch accounts through Bilibili QR login without requesting a password.
- Automatically record live sessions to timeline-aligned 30-minute MKV segments.
- Recover from API, danmaku, CDN, FFmpeg and stalled-stream failures.
- Capture live start/stop, danmaku, room changes, gifts, Super Chat and guard purchases.
- Play all recording segments on one continuous timeline with seeking, volume,
  fullscreen playback, playback speed and search-to-seek.
- Generate and replace a local faster-whisper transcript, then search it beside
  other session events.
- Export a selected range across recording segments as MP4, MKV or WebM and
  remember the last export directory.
- Minimize to the Windows tray and show native live-state, recovery, disk-space
  and error notifications.

Credentials, recordings, logs, speech models, transcripts and application
settings stay on the local machine. They are excluded from the repository.

## Build from source

Source builds require Java 21+, Maven 3.9+, FFmpeg/FFprobe 8+, 64-bit VLC 3.x
and optionally Python 3.9+ for speech recognition.

```shell
mvn clean package
./scripts/run-desktop.ps1
```

The launcher runs an isolated JAR copy so a later Maven build cannot corrupt an
open tray process. Build and test output stays under the locally ignored
`target/` directory.

## Command-line diagnostics

```shell
# Check a room once or monitor continuously
java -jar target/bili-record.jar 92613
java -jar target/bili-record.jar 92613 --watch

# Inspect stream candidates or record a 30-second sample
java -jar target/bili-record.jar 92613 --streams
java -jar target/bili-record.jar 92613 --record 30

# Run automatic recording or listen to danmaku for 30 seconds
java -jar target/bili-record.jar 92613 --auto
java -jar target/bili-record.jar 92613 --danmaku 30

# Open QR login directly
java -jar target/bili-record.jar --login
```

The CLI never prints signed stream URLs or authentication cookies. QR login
data is stored only in `data/auth/cookies.json`.

## Local data

Each automatic recording creates a directory under
`recordings/room_<room-id>/<timestamp>/` with MKV segments, FFmpeg logs,
`timeline.sqlite` and `raw-events.jsonl`. SQLite stores session anchors,
segments and normalized display events. Raw JSONL retains all decoded server
events while the UI displays only the supported event types.

The recorder keeps the input connection open when FFmpeg starts a new segment,
so a scheduled 30-minute boundary does not intentionally drop media. Real
reconnect gaps remain visible on the unified session timeline and are omitted
when exporting across segments.

## Release process

The `Windows release` GitHub Actions workflow runs the test suite, builds a
self-contained per-user installer, verifies each bundled runtime, publishes a
SHA-256 checksum and creates a GitHub Release. Release builds and test artifacts
remain in GitHub Actions and are not committed to `main`.

## Status

- [x] Room monitoring and stream resolution
- [x] QR authentication and local credential storage
- [x] Automatic recording, danmaku capture, storage and session clock
- [x] Recovery, health monitoring, desktop UI and native notifications
- [x] V2 playback, unified timeline and search-to-seek
- [x] V2.1 local transcription, transcript search and clip export
