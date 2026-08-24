# biliRecord

A private Bilibili live monitor and recorder. It currently monitors room status
and resolves available live stream variants.

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
records a requested duration; automatic start, stop, segmentation and recovery
belong to the later lifecycle phases.

## Listen to danmaku

Connect to the live-room WebSocket, authenticate, send heartbeats and print
`DANMU_MSG` events for a requested duration:

```shell
java -jar target/bili-record.jar 92613 --danmaku 30
```

The binary decoder supports normal packets, nested packets, zlib and Brotli.
Other event packets are decoded but ignored by the CLI until the storage phase
adds raw-event persistence.

The one-shot command prints `LIVE` when `live_status` is `1`; other room states
print `OFFLINE`.

## Status

- [x] Phase 1: room monitor
- [x] Phase 2: stream resolver
- [x] Authentication prerequisite: QR login and local storage
- [x] Phase 3: recorder
- [x] Phase 4: danmaku client
- [ ] Phase 5: storage
- [ ] Phase 6: session clock
- [ ] Phase 7: lifecycle
- [ ] Phase 8: recovery

Authentication data and recordings are local-only and must not be committed.
