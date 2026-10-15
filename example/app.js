var WebSocketServer = require('ws').Server
  , http = require('http')
  , express = require('express')
  , path = require('path')
  , net = require('net')
  , execFile = require('child_process').execFile
  , app = express()

var PORT = process.env.PORT || 9002
  , FWD_PORT = process.env.FWD_PORT || 1717 // adb forward 到统计 socket 的本地端口
  , ADB = process.env.ADB || 'adb'          // run_monitor 传入(可能已降级 /usr/bin/adb)
  , SERIAL = process.env.SERIAL || '';      // 多设备时由 run_monitor -s 传入

app.use(express.static(path.join(__dirname, '/public')))

var server = http.createServer(app)
var wss = new WebSocketServer({ server: server })

wss.on('connection', function(ws) {
  console.info('Got a client')

	const stream = net.createConnection({ port: FWD_PORT }, () => {
	  console.log('Connected to server!');
	});

	let buffer = '';
	stream.on('data', (chunk) => {
    // console.log('Received data:', chunk.toString('utf-8'));
	  buffer += chunk.toString('utf-8'); // 累积数据到缓冲区

	  // 假设 JSON 以换行符分隔
	  const parts = buffer.split('\n');
	  buffer = parts.pop() || ''; // 保留未完成部分

	  // 处理每个完整 JSON
	  parts.forEach(part => {
      if (part.trim() === '') return; // 忽略空行
      try {
        const obj = JSON.parse(part);
        // console.log('Parsed JSON:', obj);
        // TODO: 触发业务逻辑
        ws.send(JSON.stringify(obj))
      } catch (err) {
        console.error('Failed to parse JSON:', part);
      }
	  });
	});

	stream.on('end', () => {
	  console.log('Connection closed');
	});

	stream.on('error', function() {
    console.error('Be sure to run `adb forward tcp:1717 localabstract:scrcpy_encode_stat`')
    process.exit(1)
	})

  // 浏览器 → node 反向控制:设置/清除监控的 APP 包名
  ws.on('message', function (raw) {
    try {
      var m = JSON.parse(raw);
      if (Object.prototype.hasOwnProperty.call(m, 'setPkg')) {
        m.setPkg ? startPkgSampler(m.setPkg) : stopPkgSampler();
      }
    } catch (e) { /* 非 JSON 忽略 */ }
  });

  ws.on('close', function () {
    console.info('Lost a client')
    stream.end()
  })

  ws.on('error', (err) => {
    console.error('WebSocket error:', err);
  });
})

/* ---------------- 指定 APP 采样(浏览器输入包名驱动) ----------------
 * 1Hz 一次 adb shell:pidof → smaps_rollup(Pss/Rss) + /proc/<pid>/stat(utime+stime)。
 * CPU 为 ticks 差分(单核口径,多线程可 >100%);合成 sysStat 消息推给浏览器,
 * 前端按同秒合并进秒级记录,与 server 侧 sysStat 协议兼容 */
var pkgTimer = null;
var pkgLastTicks = -1;
var pkgLastMs = -1;

function adbShell(cmd, cb) {
  var args = SERIAL ? ['-s', SERIAL, 'shell', cmd] : ['shell', cmd];
  execFile(ADB, args, { timeout: 3000 }, function (err, stdout) {
    cb(err ? null : stdout);
  });
}

function round1(v) { return Math.round(v * 10) / 10; }

function stopPkgSampler() {
  if (pkgTimer) { clearInterval(pkgTimer); pkgTimer = null; }
  pkgLastTicks = -1; pkgLastMs = -1;
}

function startPkgSampler(pkg) {
  if (!/^[A-Za-z0-9._]+$/.test(pkg)) { console.error('bad pkg: ' + pkg); return; }
  stopPkgSampler();
  console.log('sampling app: ' + pkg);
  var pid = null; // 缓存 pid:app 重启外不再每次 fork pidof,降低网络 adb 往返抖动
  var lastRd = -1, lastWr = -1, lastIoMs = -1;
  function cmd() {
    var prefix = pid
      ? 'grep -h -E "^(Pss|Rss):" /proc/' + pid + '/smaps_rollup 2>/dev/null; cat /proc/' + pid + '/stat /proc/' + pid + '/io 2>/dev/null'
      : 'P=$(pidof ' + pkg + ' | cut -d" " -f1); [ -n "$P" ] && grep -h -E "^(Pss|Rss):" /proc/$P/smaps_rollup 2>/dev/null; cat /proc/$P/stat /proc/$P/io 2>/dev/null';
    return prefix;
  }
  pkgTimer = setInterval(function () {
    adbShell(cmd(), function (out) {
      var data = {};
      if (out) {
        var statLine = out.trim().split('\n').find(function (l) { return l.indexOf(')') > 0; }) || '';
        var pidM = /^(\d+)\s+\(/.exec(statLine);
        if (pidM) pid = +pidM[1]; // stat 行首即 pid,免费刷新缓存
        var pss = /Pss:\s+(\d+)/.exec(out);
        var rss = /Rss:\s+(\d+)/.exec(out);
        var close = statLine.lastIndexOf(')');
        if (pss) data.app_pss_mb = round1(+pss[1] / 1024);
        if (rss) data.app_rss_mb = round1(+rss[1] / 1024);
        if (close >= 0) {
          var t = statLine.slice(close + 2).trim().split(/\s+/);
          if (t.length >= 13) {
            var ticks = +t[11] + +t[12]; // utime+stime,USER_HZ=100
            var now = Date.now();
            if (pkgLastTicks >= 0 && now > pkgLastMs && ticks >= pkgLastTicks) {
              data.app_cpu = round1(100 * (ticks - pkgLastTicks) / (now - pkgLastMs) * 10);
            }
            pkgLastTicks = ticks; pkgLastMs = now;
          }
        }
        // APP 磁盘 IO:/proc/<pid>/io 的 read_bytes/write_bytes(实际块设备访问,页缓存不计)差分
        var rd = /read_bytes:\s+(\d+)/.exec(out);
        var wr = /write_bytes:\s+(\d+)/.exec(out);
        if (rd && wr) {
          var ioNow = Date.now();
          if (lastRd >= 0 && ioNow > lastIoMs) {
            data.app_io_r_kbs = round1((+rd[1] - lastRd) / (ioNow - lastIoMs));
            data.app_io_w_kbs = round1((+wr[1] - lastWr) / (ioNow - lastIoMs));
          }
          lastRd = +rd[1]; lastWr = +wr[1]; lastIoMs = ioNow;
        }
      } else if (pid) {
        pid = null; // 进程消失:清缓存,下轮重查 pidof(app 可能已重启)
      }
      if (Object.keys(data).length) {
        var msg = JSON.stringify({ type: 'sysStat', data: data });
        wss.clients.forEach(function (c) { if (c.readyState === 1) c.send(msg); });
      }
    });
  }, 1000);
}

server.listen(PORT)
console.info('Listening on port %d', PORT)
