# example 本机开发环境说明

## 浏览器验证(本地网页/图表改动)

- 验证优先级: playwright(agent 可编程截图/断言,跨机器一致) > 系统浏览器(仅人工查看);
  omp browser 工具在本机 CDP attach 失败,勿依赖
- 【本机】Chrome for Testing 155: `~/56T/work/tools/chrome-linux64/chrome`
  (`--version` 可用;人工查看页面可用它,agent 自动验证仍走 playwright)
- 网页验证首选 playwright: `npm i playwright` +
  `PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright npx playwright install chromium`
  (下载缓存已在 `~/.cache/ms-playwright/`)
- 验证方式: `node app.js`(或假数据 ws server) + playwright 脚本打开 `http://localhost:9002/` 截图/交互

## adb

- run_monitor.sh 为 `command -v adb` 通用探测 + `ADB` env 覆盖;
  【本机】PATH 里 platform-tools 在前且其 forward/reverse 损坏,须 `ADB=/usr/bin/adb`
  (其他机器通常无需覆盖;若 forward 报错,换发行版包管理器安装的 adb)
- 统计通道转发: run_monitor.sh 从 scrcpy 日志实时抓 scid,自动
  `adb forward tcp:1717 localabstract:scrcpy_XXXXXXXX_encode_stat`;
  手工场景见 scripts/deploy_server.sh
