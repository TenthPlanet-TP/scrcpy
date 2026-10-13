#!/usr/bin/env bash
# 一条命令启动编码参数监控:
#   push server + scrcpy(--no-window --record) + 按日志抓 scid 建 forward + node Web 服务
# 退出(Ctrl-C)时自动清理:停 scrcpy/node、移除 forward、删临时录制文件
#
# 用法: scripts/run_monitor.sh [-s <serial>] [--dry-run]
#   -s <serial>   指定设备 (多设备时必须)
#   其余环境:
#     REC_FILE    录制文件,默认 /tmp/scrcpy_stat.mp4 (编码器必须有 sink,统计才会产生)
#     SCRCPY_ARGS  追加到 scrcpy 的额外参数(如 "--max-fps 60"),整体展开,可覆盖默认码率
set -e

ADB=${ADB:-$(command -v adb)}
[ -x /usr/bin/adb ] && ADB=${ADB:-/usr/bin/adb}
command -v /usr/bin/adb >/dev/null && ADB=/usr/bin/adb

SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL="$2"; shift 2 ;;
        -s*) SERIAL="${1#-s}"; shift ;;
        *) echo "未知参数: $1" >&2; exit 1 ;;
    esac
done

ROOT=$(cd "$(dirname "$0")/.." && pwd)

if [ -z "$SERIAL" ]; then
    N=$(/usr/bin/adb devices | awk 'END{print NR-2}')
    if [ "$N" -gt 1 ] 2>/dev/null; then
        SERIAL=$(/usr/bin/adb devices | awk '$NF=="device"{print $1; exit}')
        echo "(多设备,自动使用 $SERIAL;可用 -s <serial> 指定)"
    fi
fi
ADB_CMD=(/usr/bin/adb)
[ -n "$SERIAL" ] && ADB_CMD+=( -s "$SERIAL" )

APK="$ROOT/server/build/outputs/apk/debug/server-debug.apk"
SCRCPY="$ROOT/build/app/scrcpy"
REC_FILE=${REC_FILE:-/dev/null}
WEB_PORT=${WEB_PORT:-9002}
DEV_JAR=/data/local/tmp/scrcpy-server.jar
DEV_SO=/data/local/tmp/libwebrtcLibsJni.so

log() { echo "[monitor] $*"; }
die() { echo "[monitor] 错误: $*" >&2; exit 1; }

# ---------- 0. 构建检查 ----------
if [ ! -f "$SCRCPY" ]; then
    log "客户端未构建,开始构建(meson + ninja)..."
    (cd "$ROOT" && meson setup build -Dcompile_server=false -Dv4l2=false >/dev/null && ninja -C build >/dev/null)
fi
[ -f "$SCRCPY" ] || die "客户端构建失败"
if [ ! -f "$APK" ]; then
    log "server APK 不存在,开始构建(gradle assembleDebug)..."
    (cd "$ROOT" && ./gradlew -p server assembleDebug >/dev/null)
fi
[ -f "$APK" ] || die "server APK 构建失败"
[ -d "$ROOT/example/node_modules" ] || (cd "$ROOT/example" && npm install --no-audit --no-fund)

# ---------- 1. push server ----------
log "推送 server jar 与 .so..."
"${ADB_CMD[@]}" push "$APK" "$DEV_JAR" >/dev/null
ABI=$("${ADB_CMD[@]}" shell getprop ro.product.cpu.abi | tr -d '\r\n')
SO="$ROOT/server/src/main/jniLibs/$ABI/libwebrtcLibsJni.so"
if [ -f "$SO" ]; then
    "${ADB_CMD[@]}" push "$SO" "$DEV_SO" >/dev/null
else
    log "warn: 无 $ABI 的 libwebrtcLibsJni.so,qp 统计将禁用(qp=-1)"
fi

# ---------- 2. 启动 scrcpy(后台) ----------
SCRCPY_LOG=$(mktemp /tmp/scrcpy_monitor.XXXXXX.log)
[ "$REC_FILE" != "/dev/null" ] && { rm -f "$REC_FILE"; } || true
REC_FMT=()
[ "$REC_FILE" = "/dev/null" ] && REC_FMT=(--record-format=mkv) || true
log "启动 scrcpy --no-window --record $REC_FILE -b 4M ${SCRCPY_ARGS:-} ..."
SCRCPY_SERVER_PATH="$APK" PATH="/usr/bin:$PATH" \
    "$SCRCPY" ${SERIAL:+-s "$SERIAL"} --no-window --record "$REC_FILE" "${REC_FMT[@]}" \
    --no-audio --no-control --force-adb-forward --video-bit-rate 4M $SCRCPY_ARGS >"$SCRCPY_LOG" 2>&1 &
SCRCPY_PID=$!

cleanup() {
    echo ""
    log "清理中..."
    kill "$SCRCPY_PID" 2>/dev/null
    [ -n "${NODE_PID:-}" ] && kill "$NODE_PID" 2>/dev/null
    "${ADB_CMD[@]}" forward --remove tcp:1717 >/dev/null 2>&1 || true
    rm -f "$SCRCPY_LOG"
    log "已退出(注意: server 退出会自删 $DEV_JAR,下次运行会重新推送)"
}
trap cleanup EXIT INT TERM

# ---------- 3. 等统计 socket 名出现并 forward ----------
log "等待 server 启动..."
STAT_NAME=""
for i in $(seq 1 30); do
    if ! kill -0 "$SCRCPY_PID" 2>/dev/null; then
        cat "$SCRCPY_LOG" >&2
        die "scrcpy 启动失败"
    fi
    STAT_NAME=$(grep -oE 'listen localabstract:scrcpy_[0-9a-f]+_encode_stat' "$SCRCPY_LOG" | head -1 | sed 's/.*localabstract://')
    [ -n "$STAT_NAME" ] && break
    sleep 0.5
done
[ -n "$STAT_NAME" ] || { cat "$SCRCPY_LOG" >&2; die "15s 内未检测到统计 socket"; }
log "统计 socket: $STAT_NAME"
"${ADB_CMD[@]}" forward tcp:1717 "localabstract:$STAT_NAME"

# ---------- 4. 启动 Web ----------
log "启动 Web 服务(端口 $WEB_PORT)..."
node "$ROOT/example/app.js" &
NODE_PID=$!
sleep 1
kill -0 "$NODE_PID" 2>/dev/null || die "node app.js 启动失败(端口被占用?)"

IP=$(hostname -I 2>/dev/null | awk '{print $1}')
echo ""
echo "=============================================================="
echo "  监控已启动,浏览器打开:  http://${IP:-<服务器IP>}:$WEB_PORT/"
echo "  录制文件: $REC_FILE$([ "$REC_FILE" = /dev/null ] && echo ' (不落盘,仅驱动编码器)')"
echo "  按 Ctrl-C 停止并清理"
echo "=============================================================="

wait
