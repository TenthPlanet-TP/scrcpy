#!/usr/bin/env bash
# 一条命令启动编码参数监控:
#   push .so + scrcpy(默认带窗口显示) + 按日志抓 scid 建 forward + node Web 服务
# 退出(Ctrl-C)时自动清理:停 scrcpy/node、移除 forward
#
# 用法: scripts/run_monitor.sh [-s <serial>]
#   -s <serial>   指定设备 (多设备时必须)
#   其余环境:
#     REC_FILE    录制文件,默认不录(开窗口显示;仅当 SCRCPY_ARGS 关窗口时才兜底录 /dev/null)
#     SCRCPY_ARGS 追加到 scrcpy 的额外参数;无显示器主机可设 "--no-window"(自动兜底录 /dev/null 驱动编码)
#     WEB_PORT    Web 端口,默认 9002
#     FWD_PORT    统计通道 forward 本地端口,默认 1717(并行监控多设备时各配一个)
set -e

ADB=${ADB:-$(command -v adb || echo /usr/bin/adb)}
command -v "$ADB" >/dev/null || { echo "未找到 adb" >&2; exit 1; }

ORIG_ARGS="$*"
usage() {
    cat <<'EOF'
用法: scripts/run_monitor.sh [-s <serial>] [scrcpy 参数...]
  -s <serial>   指定设备 (多设备时必须)
  -h, --help    显示本帮助
  其余参数(如 --no-window)原样透传给 scrcpy,与 SCRCPY_ARGS 等效追加

环境变量:
  REC_FILE      录制文件,默认不录(开窗口显示;仅当关窗口时才兜底录 /dev/null)
  SCRCPY_ARGS   追加到 scrcpy 的额外参数,与命令行透传参数等效
  WEB_PORT      Web 端口,默认 9002
  FWD_PORT      统计通道 forward 本地端口,默认 1717(并行多设备时各配一个)
  ADB           adb 路径,默认 command -v adb
                (本机注意: platform-tools 的 adb forward 子命令损坏,
                 报 "more than one device" 时请设 ADB=/usr/bin/adb)
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL="$2"; shift 2 ;;
        -s*) SERIAL="${1#-s}"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) SCRCPY_ARGS="${SCRCPY_ARGS:-}${SCRCPY_ARGS:+ }$1"; shift ;; # 其余参数(如 --no-window)原样透传给 scrcpy
    esac
done

ROOT=$(cd "$(dirname "$0")/.." && pwd)

# 布局探测:源码树(构建产物在 build/、server/build/)或 release 包(平铺在包根)
if [ -f "$ROOT/build/app/scrcpy" ]; then
    SCRCPY="$ROOT/build/app/scrcpy"
    APK="$ROOT/server/build/outputs/apk/debug/server-debug.apk"
    JNILIBS="$ROOT/server/src/main/jniLibs"
    EXAMPLE="$ROOT/example"
    BUILD_FALLBACK=1
elif [ -f "$ROOT/scrcpy" ] && [ -f "$ROOT/scrcpy-server" ]; then
    SCRCPY="$ROOT/scrcpy"
    APK="$ROOT/scrcpy-server"
    JNILIBS="$ROOT/jniLibs"
    EXAMPLE="$ROOT/example"
    BUILD_FALLBACK=0
else
    echo "既不在源码树也不在 release 包内(缺 scrcpy/scrcpy-server)" >&2; exit 1
fi

if [ -z "$SERIAL" ]; then
    N=$("$ADB" devices | awk 'END{print NR-2}')
    if [ "$N" -gt 1 ] 2>/dev/null; then
        SERIAL=$("$ADB" devices | awk '$NF=="device"{print $1; exit}')
        echo "(多设备,自动使用 $SERIAL;可用 -s <serial> 指定)"
    fi
fi

log() { echo "[monitor] $*"; }
die() { echo "[monitor] 错误: $*" >&2; exit 1; }
# adb 能力自检:坏实现(如本机 platform-tools 的 forward)会误报
# "more than one device";此时若系统另有可用 adb 则自动降级
adb_probe() { "$1" ${SERIAL:+-s "$SERIAL"} forward --no-rebind tcp:43127 localabstract:scrcpy_adb_probe >/dev/null 2>&1; }
if ! adb_probe "$ADB"; then
    "$ADB" ${SERIAL:+-s "$SERIAL"} forward --remove tcp:43127 >/dev/null 2>&1 || true
    if [ "$ADB" != /usr/bin/adb ] && [ -x /usr/bin/adb ] && adb_probe /usr/bin/adb; then
        /usr/bin/adb ${SERIAL:+-s "$SERIAL"} forward --remove tcp:43127 >/dev/null 2>&1 || true
        log "warn: $ADB 的 forward 异常,已改用 /usr/bin/adb"
        ADB=/usr/bin/adb
    fi
fi
ADB_CMD=("$ADB")
[ -n "$SERIAL" ] && ADB_CMD+=( -s "$SERIAL" )

REC_FILE=${REC_FILE:-}
WEB_PORT=${WEB_PORT:-9002}
FWD_PORT=${FWD_PORT:-1717}
DEV_SO=/data/local/tmp/libwebrtcLibsJni.so


# ---------- 0. 构建检查(仅源码树;release 包内已带产物) ----------
if [ "$BUILD_FALLBACK" = 1 ]; then
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
fi
[ -f "$APK" ] || die "server 不存在: $APK"
[ -d "$EXAMPLE/node_modules" ] || (cd "$EXAMPLE" && npm install --no-audit --no-fund)

# ---------- 1. push .so(server jar 由 scrcpy 客户端自行 push) ----------
log "推送 libwebrtcLibsJni.so..."
ABI=$("${ADB_CMD[@]}" shell getprop ro.product.cpu.abi | tr -d '\r\n')
SO="$JNILIBS/$ABI/libwebrtcLibsJni.so"
if [ -f "$SO" ]; then
    "${ADB_CMD[@]}" push "$SO" "$DEV_SO" >/dev/null
else
    log "warn: 无 $ABI 的 libwebrtcLibsJni.so,qp 统计将禁用(qp=-1)"
fi

# ---------- 2. 启动 scrcpy(后台,默认带窗口显示、不录像;窗口即编码 sink) ----------
SCRCPY_LOG=$(mktemp /tmp/scrcpy_monitor.XXXXXX)
REC_ARGS=()
case " ${SCRCPY_ARGS:-} " in
    *--no-window*|*--no-video-playback*)
        # 关窗口时必须有录制 sink,否则 scrcpy 判定无 video sink 直接退出
        [ -n "$REC_FILE" ] && rm -f "$REC_FILE"
        REC_ARGS=(--record "${REC_FILE:-/dev/null}" --record-format=mkv) ;;
    *)
        [ -n "$REC_FILE" ] && { rm -f "$REC_FILE"; REC_ARGS=(--record "$REC_FILE"); } ;;
esac
log "启动 ${SCRCPY##*/} -b 4M ${SCRCPY_ARGS:-} ..."
SCRCPY_SERVER_PATH="$APK" ADB="$ADB" PATH="/usr/bin:$PATH" \
    "$SCRCPY" ${SERIAL:+-s "$SERIAL"} "${REC_ARGS[@]}" \
    --no-audio --no-control --force-adb-forward --video-bit-rate 4M $SCRCPY_ARGS >"$SCRCPY_LOG" 2>&1 &
SCRCPY_PID=$!

cleanup() {
    echo ""
    log "清理中..."
    kill "$SCRCPY_PID" 2>/dev/null
    [ -n "${NODE_PID:-}" ] && kill "$NODE_PID" 2>/dev/null
    "${ADB_CMD[@]}" forward --remove "tcp:$FWD_PORT" >/dev/null 2>&1 || true
    rm -f "$SCRCPY_LOG"
    log "已退出(注意: server 退出会自删设备端 jar,下次运行会自动重新推送)"
}
trap cleanup EXIT INT TERM

# ---------- 3. 等统计 socket 名出现并 forward ----------
log "等待 server 启动..."
STAT_NAME=""
for i in $(seq 1 30); do
    if ! kill -0 "$SCRCPY_PID" 2>/dev/null; then
        cat "$SCRCPY_LOG" >&2
        # platform-tools adb 的 forward 损坏时报 "more than one device"(与设备数无关)
        if grep -q "more than one device" "$SCRCPY_LOG" && [ "$ADB" != /usr/bin/adb ] && [ -x /usr/bin/adb ]; then
            echo "[monitor] 提示: 当前 adb($ADB)的 forward 异常,可重试: ADB=/usr/bin/adb $0 $ORIG_ARGS" >&2
        fi
        die "scrcpy 启动失败"
    fi
    STAT_NAME=$(grep -oE 'listen localabstract:scrcpy_[0-9a-f]+_encode_stat' "$SCRCPY_LOG" | head -1 | sed 's/.*localabstract://')
    [ -n "$STAT_NAME" ] && break
    sleep 0.5
done
[ -n "$STAT_NAME" ] || { cat "$SCRCPY_LOG" >&2; die "15s 内未检测到统计 socket"; }
log "统计 socket: $STAT_NAME"
"${ADB_CMD[@]}" forward "tcp:$FWD_PORT" "localabstract:$STAT_NAME"

# ---------- 4. 启动 Web ----------
log "启动 Web 服务(端口 $WEB_PORT)..."
PORT="$WEB_PORT" FWD_PORT="$FWD_PORT" ADB="$ADB" SERIAL="$SERIAL" node "$EXAMPLE/app.js" &
NODE_PID=$!
sleep 1
kill -0 "$NODE_PID" 2>/dev/null || die "node app.js 启动失败(端口被占用?)"

IP=$(hostname -I 2>/dev/null | awk '{print $1}')
[ -z "$IP" ] && [ "$(uname -s)" = Darwin ] && IP=$(ipconfig getifaddr en0 2>/dev/null)
echo ""
echo "=============================================================="
echo "  监控已启动,浏览器打开:  http://${IP:-<服务器IP>}:$WEB_PORT/"
echo "  录制文件: $(if [ -n "$REC_FILE" ]; then echo "$REC_FILE"; elif [ ${#REC_ARGS[@]} -gt 0 ]; then echo '/dev/null(不落盘,仅驱动编码器)'; else echo '无(窗口显示即 sink)'; fi)"
echo "  按 Ctrl-C 停止并清理"
echo "=============================================================="

wait
