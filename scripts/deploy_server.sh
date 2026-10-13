#!/usr/bin/env bash
# scrcpy server 部署脚本:构建 server、推送 jar 与 libwebrtcLibsJni.so(按设备 ABI)、建立统计通道 adb forward
#
# 用法: scripts/deploy_server.sh [-s <serial>] [命令] [命令参数...]
#   (无命令)   build + push + forward 一条龙
#   build      构建 server debug APK (gradle assembleDebug)
#   push       推送 server jar 与 .so 到 /data/local/tmp (按 ro.product.cpu.abi 自动选择)
#   forward    adb forward tcp:1717 -> localabstract:<name>,默认 scrcpy_encode_stat
#              注意: scrcpy 3.2 client 使用随机 scid 时 socket 名为 scrcpy_XXXXXXXX_encode_stat,
#              可用 "forward scrcpy_12345678_encode_stat" 指定
#   run        调试用: app_process 直接启动 server (video socket 需要有 client 连接,如 FakeVideo)
#   clean      清理设备端文件并移除 forward
#
# 环境变量:
#   ADB   指定 adb 路径 (本机 SDK platform-tools 的 adb forward 有问题,默认优先 /usr/bin/adb)
set -e
usage() {
    cat <<'EOF'
scrcpy server 部署脚本:构建 server、推送 jar 与 libwebrtcLibsJni.so(按设备 ABI)、建立统计通道 adb forward

用法: scripts/deploy_server.sh [-s <serial>] [命令] [命令参数...]

命令:
  (无命令)   build + push + forward 一条龙
  build      构建 server debug APK (gradle assembleDebug)
  push       推送 server jar 与 .so 到 /data/local/tmp (按 ro.product.cpu.abi 自动选择)
  forward    adb forward tcp:1717 -> localabstract:<name>,默认 scrcpy_encode_stat
  clean      清理设备端文件并移除 forward
  run        调试用: app_process 直接启动 server (video socket 需要有 client 连接)
  -h         显示本帮助

环境变量:
  ADB        指定 adb 路径 (本机 SDK platform-tools 的 adb forward 有问题,默认优先 /usr/bin/adb)

示例:
  # 部署到指定设备:构建 + 推送 + forward,一条龙
  ./scripts/deploy_server.sh -s 192.168.1.100:5555

  # 只推送(需先 build),ABI 自动选择
  ./scripts/deploy_server.sh -s 192.168.1.100:5555 push

  # 建立统计通道转发,然后打开 Web 图表
  ./scripts/deploy_server.sh forward
  cd example && npm install && node app.js   # 打开 http://localhost:9002

  # scrcpy 3.2 client 使用随机 scid 时,按实际 scid 指定 socket 名
  ./scripts/deploy_server.sh forward scrcpy_12345678_encode_stat

  # 调试:直接启动 server (scid=-1,socket 名为 scrcpy / scrcpy_encode_stat)
  ./scripts/deploy_server.sh run

  # 清理设备端 jar/so 并移除 forward
  ./scripts/deploy_server.sh clean

注意:
  * server 正常退出会自删 /data/local/tmp/scrcpy-server.jar (scrcpy CleanUp 机制),
    每次 scrcpy 会话后需重新 push
  * 未指定 -s 且有多台设备时自动选第一台并提示
EOF
}

ADB="$ADB"
if [ -z "$ADB" ]; then
    if [ -x /usr/bin/adb ]; then
        ADB=/usr/bin/adb
    else
        ADB=$(command -v adb) || { echo "error: adb not found" >&2; exit 1; }
    fi
fi

SERIAL=""
CMD="all"
REST=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        -s) SERIAL="$2"; shift 2 ;;
        -s*) SERIAL="${1#-s}"; shift ;;
        *) CMD="$1"; shift; REST=("$@"); break ;;
    esac
done

adb_cmd() {
    if [ -n "$SERIAL" ]; then
        "$ADB" -s "$SERIAL" "$@"
    else
        "$ADB" "$@"
    fi
}

# 未指定 serial 且多设备时,选第一台
if [ -z "$SERIAL" ]; then
    N=$("$ADB" devices | awk 'END{print NR-2}')
    if [ "$N" -gt 1 ] 2>/dev/null; then
        SERIAL=$("$ADB" devices | awk '$NF=="device"{print $1; exit}')
        echo "(多设备,自动使用 $SERIAL;可用 -s <serial> 指定)"
    fi
fi

ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK="$ROOT/server/build/outputs/apk/debug/server-debug.apk"
DEV_JAR=/data/local/tmp/scrcpy-server.jar
DEV_SO=/data/local/tmp/libwebrtcLibsJni.so
STAT_PORT=1717
STAT_SOCKET=${REST[0]:-scrcpy_encode_stat}

do_build() {
    (cd "$ROOT" && ./gradlew -p server assembleDebug)
}

do_push() {
    [ -f "$APK" ] || { echo "error: $APK 不存在,先运行 build" >&2; exit 1; }
    echo "[push] server jar -> $DEV_JAR"
    adb_cmd push "$APK" "$DEV_JAR" >/dev/null
    ABI=$(adb_cmd shell getprop ro.product.cpu.abi | tr -d '\r\n')
    SO="$ROOT/server/src/main/jniLibs/$ABI/libwebrtcLibsJni.so"
    if [ -f "$SO" ]; then
        echo "[push] $ABI/libwebrtcLibsJni.so -> $DEV_SO"
        adb_cmd push "$SO" "$DEV_SO" >/dev/null
    else
        echo "[push] warn: 无 $ABI 版本的 libwebrtcLibsJni.so,qp 统计将禁用(qp=-1)" >&2
    fi
}

do_forward() {
    echo "[forward] tcp:$STAT_PORT -> localabstract:$STAT_SOCKET"
    adb_cmd forward "tcp:$STAT_PORT" "localabstract:$STAT_SOCKET"
    echo "[forward] Web 图表: cd example && npm install && node app.js, 打开 http://localhost:9002"
}

do_clean() {
    adb_cmd shell "rm -f $DEV_JAR $DEV_SO"
    adb_cmd forward --remove "tcp:$STAT_PORT" >/dev/null 2>&1 || true
    echo "[clean] 已删除设备端文件并移除 forward"
}

case "$CMD" in
    build) do_build ;;
    push) do_push ;;
    forward) do_forward ;;
    clean) do_clean ;;
    run)
        # scid 缺省为 -1,socket 名为 scrcpy / scrcpy_encode_stat
        exec adb_cmd shell "CLASSPATH=$DEV_JAR app_process / com.genymobile.scrcpy.Server 3.2 log_level=debug audio=false control=false ${REST[*]}"
        ;;
    all) do_build; do_push; do_forward ;;
    *) echo "未知命令: $CMD (可用: build push forward run clean)" >&2; exit 1 ;;
esac
