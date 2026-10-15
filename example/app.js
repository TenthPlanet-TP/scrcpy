var WebSocketServer = require('ws').Server
  , http = require('http')
  , express = require('express')
  , path = require('path')
  , net = require('net')
  , app = express()

var PORT = process.env.PORT || 9002
  , FWD_PORT = process.env.FWD_PORT || 1717; // adb forward 到统计 socket 的本地端口

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

  ws.on('close', function() {
    console.info('Lost a client')
    stream.end()
  })

  ws.on('error', (err) => {
    console.error('WebSocket error:', err);
  });
})

server.listen(PORT)
console.info('Listening on port %d', PORT)
