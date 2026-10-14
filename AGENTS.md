# Repository Guidelines

## Project Overview

scrcpy (v3.2):在 PC 上显示并控制 Android 设备。两部分组成:C 客户端(本仓库 `app/`,依赖 FFmpeg + SDL2)和运行在设备上的 Java server(`server/`,经 `adb push` + `app_process` 启动,非 APK 安装)。两者通过 adb 隧道上的 video/audio/control 三个 socket 通信。

## Architecture & Data Flow

- 入口 `app/src/main.c` → `scrcpy()`(`app/src/scrcpy.c`,唯一编排点)。OTG 模式绕过 server/socket 走 `usb/scrcpy_otg.c`。
- 正向数据流:`server.c` 线程(adb push + app_process + reverse/forward 隧道 + 3 socket)→ 每流一个 `demuxer.c` 线程(FFmpeg 解析 AVPacket)→ packet_sink(`decoder.c`、`recorder.c`)→ frame_sink(`screen.c` 渲染、`audio_player.c`、`v4l2_sink.c`),中间可插 `delay_buffer.c`。
- 反向控制:SDL 事件循环 → `input_manager.c` → key/mouse/gamepad processor(SDK/UHID/AOA 三种可换后端)→ `controller.c` 消息队列 → control socket;设备回传由 `receiver.c` + `device_msg.c` 处理(剪贴板等)。
- 核心抽象:`app/src/trait/` 的 source/sink(packet、frame、input processor);组件内嵌 trait 结构体 + `*_add_sink()` 连接,最多 2 个 sink,无回压。
- 并发模型:每组件一线程;生命周期统一 `init → start → stop → join → destroy`;`scrcpy()` 用 `xxx_started/initialized` 标志在 `goto end` 处逆序清理——新增组件必须遵循该模式。
- 线程通信:① SDL 自定义事件(`events.h`,`SC_EVENT_*`;跨线程投递任务用 `SC_EVENT_RUN_ON_MAIN_THREAD` + runnable);② `const struct sc_*_cbs` 回调结构体(组件线程调用,回调内 push 事件回主线程)。
- 协议细节以 `doc/develop.md` 为准(socket 协议、ControlMessage/DeviceMessage 序列化、JDWP 调试)。
- 编码监控分支(encode_stat):server 端 `encodeStat/EncodeStatSender` 在第 4 个 socket `scrcpy_<scid>_encode_stat` 上向所有客户端广播逐帧编码统计(pts/size/qp/encode_cost/isKeyFrame + 汇总 framerate/bitrate),多客户端各自独立队列、drop-oldest;`example/` 的 Node Web 页订阅展示,`scripts/run_monitor.sh` 一键拉起全链路。

## Key Directories

| 目录 | 用途 |
|---|---|
| `app/src/` | C 客户端源码;`trait/` 抽象、`util/`(net/thread/log/vecdeque 等)、`adb/`、`sys/{unix,win}/` 平台层 |
| `server/src/` | Android Java server(`com.genymobile.scrcpy`) |
| `app/tests/` | C 单元测试(11 个独立 main 程序) |
| `app/deps/` | 依赖源码静态构建脚本(SDL/FFmpeg/dav1d/libusb/adb) |
| `release/` | 发布流水线脚本(构建/打包/测试/校验和) |
| `config/` | Java Checkstyle 配置 |
| `doc/` | 用户文档(每特性一页)+ `build.md`/`develop.md` |
| `example/` | 编码监控 Web 页(Node `app.js` + `public/`,ws 订阅统计 socket,6 图表)+ 独立 client 协议示例 |
| `scripts/` | 辅助脚本:`run_monitor.sh`(一键监控)、`make_release.sh`(一键打包)、`deploy_server.sh`(手工部署 server) |

## Development Commands

```bash
# 客户端(debug 默认,含测试)
meson setup build
ninja -C build
./run build [scrcpy options]      # 免安装运行,自动设 SCRCPY_SERVER_PATH

# 测试
meson test -C build               # C 测试(仅 debug buildtype)
release/test_client.sh            # CI 同款:ASan+UBSan + ninja test
./gradlew -p server check         # Java server 测试 + checkstyle

# 发布构建
meson setup build --buildtype=release --strip -Db_lto=true
ninja -C build && ninja -C build install
scripts/make_release.sh [version]  # 一键发布包(release 客户端+server jar+监控链路 → release/output/*.tar.gz)

# server(也可被 meson 统一构建)
server/build_without_gradle.sh    # 无 gradle 手工构建(javac+d8)
```

版本号统一用 `./bump_version X.Y.Z` 同步。贡献基于 `dev` 分支(master 仅发布)。

## Code Conventions & Common Patterns

- **C 端**:类型/函数带 `sc_` 前缀;每个文件第一个 include 为 `common.h`(引入 meson 生成的 `config.h`);编译期特性用 `HAVE_USB`/`HAVE_V4L2`/`PORTABLE` 条件分支;错误处理用返回 bool + `LOG_*` 宏(`util/log.h`,含 `LOG_OOM`);容器用侵入式宏 `vecdeque.h`/`container_of.h`;跨平台线程/网络封装在 `util/thread.c`/`util/net.c`,不直接用 pthread。
- **Java 端(server)**:包 `com.genymobile.scrcpy.*`;Checkstyle(禁 Tab、禁行尾空格、文件末尾换行)。
- **新组件清单**:实现 trait source/sink、`sc_` 回调 cbs 结构体、init/start/stop/join/destroy 生命周期、在 `scrcpy.c` 注册并纳入逆序清理。
- 可中断阻塞操作用 `util/intr.c` + `net_intr.c`/`process_intr.c`,不要裸 blocking 调用。

## Important Files

- `app/src/scrcpy.c` — 编排中心,理解启动/清理从这里入手
- `app/src/cli.c` + `options.c` — 参数解析与 `struct scrcpy_options`(指定初始化器风格)
- `app/src/server.c`、`app/src/adb/` — adb 集成与隧道
- `app/src/events.h` — 全部自定义 SDL 事件定义
- `app/src/control_msg.c` / `device_msg.c` — 客户端↔server 协议序列化(与 server 端 Java 类互为镜像)
- `meson.build`、`meson_options.txt` — 选项:`compile_app/server`、`prebuilt_server`、`portable`、`static`、`server_debugger`、`v4l2`、`usb`
- `server/build.gradle` — compileSdk 35, minSdk 21, JUnit 4.13.2

## Runtime/Tooling Preferences

- 客户端构建:meson ≥ 0.49 + ninja;依赖 FFmpeg(libavformat/codec/util/swresample)、SDL2 ≥ 2.0.5,可选 libusb-1.0/libavdevice。
- server:JDK 17 + Gradle 8.9 wrapper;Android SDK 平台 35。
- 无 clang-format/editorconfig;C 风格跟随现有代码;Java 风格由 Checkstyle 强制。
- CI:`.github/workflows/release.yml` 仅手动触发(workflow_dispatch),无 push/PR CI。
- `example/AGENTS.md`(若存在):example 目录的本机开发环境补充说明(浏览器验证工具路径、adb 注意事项),本地文件不入库;改动 example/ 前先读它。

## Testing & QA

- **C 测试**(`app/tests/`):无第三方框架,每个 `test_<module>.c` 是含 `main()` 的独立程序,用 `assert()` 断言、`static void test_xxx(void)` 组织;仅 debug buildtype 下由 meson 注册(assert 在 release 下被编译掉)。源码用 `-DSC_TEST` 宏暴露测试钩子。
- 新增 C 测试:在 `app/meson.build` 末尾 `### TESTS` 段声明 `[name, [test.c, 被测源文件...]]`(直接重编被测源码而非链接库,附加 `src/compat.c`)。
- **Java 测试**:`server/src/test/`,JUnit4,类名 `<被测类>Test`、方法 `testXxx`,包路径镜像 main。
- 协议两端测试互为镜像:`test_control_msg_serialize.c` ↔ `ControlMessageReaderTest.java`、`test_device_msg_deserialize.c` ↔ `DeviceMessageWriterTest.java`——修改协议须两端同步更新。
- 完整验证:`release/test_client.sh`(ASan+UBSan)+ `release/test_server.sh`。
