# biliRecord

A private Bilibili live monitor and recorder. The current first phase resolves a
room ID and reports whether the room is live.

## Requirements

- Java 21 or newer
- Maven 3.9 or newer

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

The one-shot command prints `LIVE` when `live_status` is `1`; other room states
print `OFFLINE`.

## Status

- [x] Phase 1: room monitor
- [ ] Phase 2: stream resolver
- [ ] Phase 3: recorder
- [ ] Phase 4: danmaku client
- [ ] Phase 5: storage
- [ ] Phase 6: session clock
- [ ] Phase 7: lifecycle
- [ ] Phase 8: recovery

Authentication data and recordings are local-only and must not be committed.
