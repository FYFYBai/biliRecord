# biliRecord

[English](README.md)

白沉的录播小工具是一款带 Windows 桌面界面的 Bilibili 直播监测、录制与回看工具。它可以监测直播间状态，自动录制完整场次，保存可搜索的事件时间轴，播放本地录像，并在本地生成语音转录。

## 下载

请从 [GitHub Releases](https://github.com/FYFYBai/biliRecord/releases) 下载 Windows 自包含安装包。安装包已经包含 Java 运行时、FFmpeg/FFprobe、VLC、Python 和 faster-whisper，使用者不需要自行安装或配置开发环境。

第一次生成转录时仍需联网下载用户所选的语音识别模型。模型、录像和账户信息均保存在本机，不会提交到仓库。

## 桌面功能

- 输入直播间号或 Bilibili 直播链接进行监测，并记住最近选择的房间。
- 通过 Bilibili 二维码登录或切换账户，不要求输入密码。
- 开播后自动录制，并生成与时间轴对齐的 30 分钟 MKV 分段。
- 自动处理房间 API、弹幕连接、CDN、FFmpeg 和录制卡死等异常。
- 记录开关播、弹幕、标题或分区变化、送礼、醒目留言和购买舰长等事件。
- 将一个场次的全部录像分段放在同一时间轴播放，支持点击播放、拖动进度、音量、静音、倍速、全屏及搜索定位。
- 使用 faster-whisper 在本地生成转录；重新生成时会替换该场次原有转录，不会累积重复记录。
- 按起止时间跨分段导出 MP4、MKV 或 WebM，并自动记住上次选择的导出目录。
- 最小化到 Windows 托盘，通过系统通知提示开关播、恢复成功、磁盘空间不足和运行错误。

账户凭据、录像、日志、语音模型、转录和软件设置只保存在本机，并已从 Git 仓库排除。

## 从源码构建

源码开发需要 Java 21 或更高版本、Maven 3.9 或更高版本、FFmpeg/FFprobe 8 或更高版本及 64 位 VLC 3.x。只有在不使用 Release 自带运行时且需要转录时，才需另外安装 Python 3.9 或更高版本。

```shell
mvn clean package
java -jar target/bili-record.jar
```

构建和测试输出均留在本地已忽略的 `target/` 目录中。

## 命令行诊断

```shell
# 单次检查或持续监测直播间
java -jar target/bili-record.jar 92613
java -jar target/bili-record.jar 92613 --watch

# 查看直播流候选或录制 30 秒样本
java -jar target/bili-record.jar 92613 --streams
java -jar target/bili-record.jar 92613 --record 30

# 自动录制或监听 30 秒弹幕
java -jar target/bili-record.jar 92613 --auto
java -jar target/bili-record.jar 92613 --danmaku 30

# 直接打开二维码登录
java -jar target/bili-record.jar --login
```

命令行不会输出带签名的直播流地址或登录 Cookie。二维码登录数据仅保存在 `data/auth/cookies.json`。

## 本地数据

每次自动录制都会在 `recordings/room_<房间号>/<时间>/` 下创建独立目录，其中包含 MKV 录像分段、FFmpeg 日志、`timeline.sqlite` 和 `raw-events.jsonl`。

SQLite 保存场次锚点、录像分段和供界面显示的标准化事件。原始 JSONL 会保留服务端发来的全部已解码事件，而界面只显示当前支持的事件类型。转录内容同时写入该场次的 `timeline.sqlite` 和 `transcript.jsonl`。

正常录制时，FFmpeg 在约 30 分钟的关键帧处开启新分段，但不会主动断开输入流，因此计划分段不会故意丢失画面。真实的断线重连间隔会保留在统一时间轴上；跨分段导出时会自动跳过这些空档。

## 登录与隐私

正式登录流程使用 Bilibili 二维码，不读取现有浏览器中的 Cookie，也不会请求密码。Cookie 与刷新令牌仅保存在本地已忽略的 `data/auth/cookies.json`。转录音频和文字不会上传到本项目的服务器。

## GitHub Release

仓库中的 `Windows release` GitHub Actions 工作流会运行完整测试，生成自包含的 Windows 用户级安装包，验证随包运行时，生成 SHA-256 校验文件，并创建 GitHub Release。Release 构建产物和测试产物只保留在 GitHub Actions/Releases 中，不会提交到 `main`。

## 当前进度

- [x] 直播间监测与直播流解析
- [x] 二维码登录与本地凭据存储
- [x] 自动录制、弹幕采集、存储和场次时钟
- [x] 异常恢复、录制健康检查、桌面界面与系统通知
- [x] V2 本地回放、统一时间轴和搜索定位
- [x] V2.1 本地转录、转录搜索和片段导出
