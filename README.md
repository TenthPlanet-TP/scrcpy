# 云手机编码测试工具

基于 [scrcpy](https://github.com/Genymobile/scrcpy) v3.2 改造的 Android 编码质量监控工具:server 端逐帧采集编码器统计(帧大小、QP、编码耗时、帧率、码率),经 abstract socket 以 JSON 行协议输出,Web 端实时图表展示。

![编码监控运行截图](example/screenshot.png)

## 监控指标

| 图表 | 说明 |
|---|---|
| FrameSize(KB) | 每帧编码后大小,I 帧红色 / P 帧蓝色 |
| QP | 量化参数(需设备端 `libwebrtcLibsJni.so`,缺失时为 -1) |
| encode_cost(ms) | 单帧编码耗时 |
| interval_jitter(ms) | 帧间隔抖动(客户端由相邻 `pts_ms` 差推导) |
| FPS / Bitrate(Mbps) | server 端每秒汇总 |

## 快速开始

```bash
# 一条命令:push server + 启动 scrcpy + 建隧道 + 起 Web 服务(默认 4Mbps)
./scripts/run_monitor.sh

# 浏览器打开
http://<本机IP>:9002/
```

环境变量:

| 变量 | 默认 | 说明 |
|---|---|---|
| `SCRCPY_ARGS` | 空 | 追加 scrcpy 参数,如 `"--max-fps 60"`、`"--video-codec h265"`,可覆盖默认码率 |
| `REC_FILE` | `/dev/null` | 录制文件(编码器必须有 sink 统计才产生) |
| `WEB_PORT` | `9002` | Web 端口 |

仅部署 server(不启动监控):

```bash
./scripts/deploy_server.sh -h
```

## 架构

```mermaid
flowchart LR
    A[视频源] --> B[MediaCodec]
    B --> C[EncodeUtils 逐帧统计]
    C -->|JSON 行| D["localabstract socket<br/>scrcpy_&lt;scid&gt;_encode_stat"]
    D -->|adb forward tcp:1717| E[example/app.js]
    E -->|WebSocket| F["浏览器 public/index.html<br/>6 张实时图表"]
```

- 统计通道独立于 scrcpy 的 video/audio/control 三通道,多客户端同时监控各自拿到完整数据流(广播)
- 协议字段详见 `example/README.md`

## 相关目录

| 目录 | 用途 |
|---|---|
| `server/src/main/java/com/genymobile/scrcpy/encodeStat/` | server 端统计采集与发送 |
| `example/` | Node WebSocket 中继 + Web 监控页 |
| `scripts/run_monitor.sh` | 一键监控链路 |
| `scripts/deploy_server.sh` | server 构建与部署 |

上游 scrcpy 的完整文档见 [doc/](doc/)。
