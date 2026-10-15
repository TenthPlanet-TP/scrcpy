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

每条消息一行 JSON,`\n` 分隔,三类:

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

**frameStat** — 每秒一条汇总(仅在有编码帧的秒发出):

```json
{"type":"frameStat","data":{"framerate":30,"bitrateKbps":4000}}
```

**sysStat** — 每秒一条系统指标(独立线程,与编码无关;字段按设备可用性缺席)。
server 端产整机指标;指定 APP 指标由本目录 `app.js` 经 adb shell 采样后以同一消息类型注入:

```json
{"type":"sysStat","data":{"cpu_total":22.5,"mem_total_mb":3840,"mem_avail_mb":2449,"net_rx_kbs":4.4,"net_tx_kbs":6.2,"temp_c":26.2,"batt_pct":70,"gpu_util":6,"io_rx_kbs":0,"io_tx_kbs":1024.5,"app_cpu":2.6,"app_pss_mb":66.8,"app_rss_mb":210.4,"app_io_r_kbs":0,"app_io_w_kbs":12.3}}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `cpu_total` | double | 整机 CPU 占用(%) |
| `mem_total_mb` / `mem_avail_mb` | double | 整机内存总量 / 可用量(MB) |
| `net_rx_kbs` / `net_tx_kbs` | double | 设备网络吞吐(不含 lo,KB/s) |
| `temp_c` | double | 电池温度(℃) |
| `batt_pct` | int | 电量(%) |
| `gpu_util` | int | GPU 占用(%),Mali 平台(`mali0/device/utilisation`);其余平台缺省 |
| `io_rx_kbs` / `io_tx_kbs` | double | 整机磁盘读/写吞吐(`/proc/diskstats` 扇区差分,排除 loop/ram/zram,KB/s) |
| `app_cpu` | double | 指定 APP 的 CPU(`app.js` 采样;单核口径,多线程可 >100%) |
| `app_pss_mb` / `app_rss_mb` | double | 指定 APP 的 PSS / RSS(`smaps_rollup`,MB) |
| `app_io_r_kbs` / `app_io_w_kbs` | double | 指定 APP 的磁盘读/写(`/proc/<pid>/io`,实际块设备访问,KB/s) |

注意:

- `bitrateKbps` 为十进制 kbps(与 MediaCodec/ffprobe 一致),页面按 `/1000` 换算 Mbps
- server 不下发帧间隔,`interval_jitter(ms)` 由客户端取相邻 `pts_ms` 差、再减慢速 EMA 基准得出
- 多客户端同时连接各自拿到完整数据流(server 端按客户端广播)

## Web 页面

- 两个 Tab(**页面与控件全英文**;tab 在 header 最左,header 控件随 tab 切换:EncodeStats 显示 drop 徽标+Export CSV,PerfCat 显示设备状态徽标+包名输入+Export 1Hz;单页、2 列卡、后台 Tab 数据照常采集):
  - **EncodeStats**:FrameSize(KB)、QP、encode_cost(ms)、out−in & jitter(ms)双线卡、EncodeFPS(编码器出帧率)、Bitrate(Mbps);out−in(橙)=编码器出帧间隔−产帧间隔,正常贴 0,正值成簇=编码器攒帧
  - **PerfCat**:SysFPS(显示层产帧率,pts 差分,≈目标 APP 渲染帧率;红色竖线=perfDog 口径 Jank 卡顿标记,帧间隔同时 >前三帧均值×2 且 >83.3ms,stats 尾部 `jank N` 为当前秒次数,CSV 导出含 jank 列;受 --max-fps 限帧影响,限帧后判定偏保守)、CPU(%)(整机+指定 APP)、AppPSS(MB)、Net(KB/s)(rx/tx)、GPU(%)、IO(KB/s)(磁盘读/写);慢速指标曲线 gapJoin(跨缺口连线,不断线)
- PerfCat 顶部输入框填 **APP 包名**回车 → 开始采样该 APP 的 CPU/PSS/IO(清空回车停止)
- 「导出 CSV」帧级数据(seq/pts/ts/size/qp/cost/interval 等);「导出秒级 CSV」fps/码率/系统指标汇总
- 每卡右上角 y 轴量程徽章点击循环 1/4→1/2→1→2→4 倍;多线卡图例色块点击显隐对应曲线
- 断线自动重连(2s);编码器静止时无帧输出属正常现象
