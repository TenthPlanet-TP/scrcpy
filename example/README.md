# Example: 编码统计 Web 监控

将 server 端的编码统计(JSON 行协议)经 WebSocket 转发给浏览器实时图表展示。兼作协议参考实现。

## 架构

```
server (app_process)                    主机
┌─────────────────────┐   adb forward   ┌──────────┐   WebSocket   ┌─────────────┐
│ EncodeStatSender    │  tcp:1717 ────► │ app.js   │ ────────────► │ index.html │
│ localabstract:      │                 │ 中继+按行 │               │ 6 张实时图表 │
│ scrcpy_<scid>_encode_stat             │ JSON 解析 │               │             │
└─────────────────────┘                 └──────────┘               └─────────────┘
```

统计 socket 名含 scid(每次会话随机),由 server 日志 `listen localabstract:...` 获取;
`scripts/run_monitor.sh` 已自动完成抓取与 `adb forward tcp:1717`。

## 运行

```bash
# 前提:scrcpy 已在跑(统计随编码产生),并已建立转发
adb forward tcp:1717 localabstract:scrcpy_<scid>_encode_stat

npm install          # 首次:ws + express
node app.js          # 默认端口 9002,PORT 环境变量可改
```

浏览器打开 `http://localhost:9002/`。

## 协议:JSON 行

每条消息一行 JSON,`\n` 分隔,两类:

**frame** — 每编码一帧一条:

```json
{"type":"frame","data":{"pts_ms":123456,"size":40960,"qp":35,"encode_cost_ms":8,"isKeyFrame":false,"seq":42,"ts_ms":123464}}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `pts_ms` | long | buffer pts(presentationTimeUs/1000,设备 uptime 基准);会话首帧为 0 |
| `size` | int | 编码后字节数(config 帧为 codec 头,`qp=-1`) |
| `qp` | int | 量化参数,需设备端 `libwebrtcLibsJni.so`;缺失时恒 -1 |
| `encode_cost_ms` | long | 采集时刻与 pts 的差(近似编码耗时);首帧(pts=0)为 0 |
| `isKeyFrame` | bool | 是否 I 帧 |
| `seq` | long | 递增序号;跳变说明广播队列丢帧,页面记入 frame_drop |
| `ts_ms` | long | 采集时刻(编码器出帧时间,uptime 基准);与 `pts_ms` 差分对比可定位丢帧层 |

**frameStat** — 每秒一条汇总:

```json
{"type":"frameStat","data":{"framerate":30,"bitrateKbps":4000}}
```

注意:

- `bitrateKbps` 为十进制 kbps(与 MediaCodec/ffprobe 一致),页面按 `/1000` 换算 Mbps
- server 不下发帧间隔,`interval_jitter(ms)` 由客户端取相邻 `pts_ms` 差、再减慢速 EMA 基准得出
- 多客户端同时连接各自拿到完整数据流(server 端按客户端广播)

## Web 页面

- 6 张图表:FrameSize(KB)、QP、encode_cost(ms)、out−in & jitter(ms)、FPS、Bitrate(Mbps);其中 out−in(亮线)=编码器出帧间隔−产帧间隔,正常贴 0,正值成簇=编码器攒帧;jitter(淡线)=产帧间隔相对慢速基准的偏差,反映显示层抖动/掉帧尖峰
- 顶部徽标:`in/out`(最近 1s 平均帧间隔,in=显示层产帧 pts 差,out=编码器出帧 ts 差,两者同步变化→显示层产帧不足,in 正常 out 成簇→编码器攒帧)、`丢帧`(server seq 跳变累计,非 0 时当前曲线不完整)
- 「导出 CSV」导出当前窗口帧级数据(seq/pts/ts/size/qp/cost/interval 等)
- 每卡右上角 y 轴量程徽章(如 `±40`/`75`)点击循环 1/4→1/2→1→2→4 倍缩放;`out−in & jitter` 卡的图例色块可点击单独显示/隐藏对应曲线
- 断线自动重连(2s);编码器静止时无帧输出属正常现象
