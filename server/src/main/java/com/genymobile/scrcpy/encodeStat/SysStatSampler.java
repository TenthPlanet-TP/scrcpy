package com.genymobile.scrcpy.encodeStat;

import android.os.SystemClock;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * 数据源均为 shell uid 可读的标准接口:/proc/stat、/proc/meminfo、
 * /proc/net/dev、/sys/class/power_supply/battery。
 * CPU/网络为差分值,首次采样只报绝对量(内存/温度/电量),差分字段缺省。
 * 指定 APP 的 CPU/内存不在此处:由 example/app.js 经 adb shell 采样(浏览器输入包名驱动)。
 */
public final class SysStatSampler {

    public interface OnStatCb {
        void callback(String json);
    }

    private static final long INTERVAL_MS = 1000;
    private static final int KB = 1024;

    private Thread thread;
    private final OnStatCb cb;

    // 上次采样累计值(无则 -1)
    private long lastCpuBusy = -1;
    private long lastCpuTotal = -1;
    private long lastRxBytes = -1;
    private long lastTxBytes = -1;
    private long lastRdSectors = -1;
    private long lastWrSectors = -1;
    private long lastUptime = -1;

    public SysStatSampler(OnStatCb cb) {
        this.cb = cb;
    }

    public void start() {
        thread = new Thread(this::loop, "sys-stat-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            long uptime = SystemClock.uptimeMillis();
            JSONObject data = sample(uptime);
            if (data != null && cb != null) {
                JSONObject json = new JSONObject();
                jsonPut(json, "type", "sysStat");
                jsonPut(json, "data", data);
                cb.callback(json.toString());
            }
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // package-private 便于状态复用测试;返回 null 表示无任何可报字段
    JSONObject sample(long uptime) {
        JSONObject data = new JSONObject();

        long[] cpu = readProcStat();
        if (cpu != null && lastCpuTotal >= 0 && cpu[1] > lastCpuTotal) {
            jsonPut(data, "cpu_total", round1(100.0 * (cpu[0] - lastCpuBusy) / (cpu[1] - lastCpuTotal)));
        }


        long[] mem = readMeminfo();
        if (mem != null) {
            jsonPut(data, "mem_total_mb", round1(mem[0] / (double) KB));
            jsonPut(data, "mem_avail_mb", round1(mem[1] / (double) KB));
        }


        long[] net = readNetDev();
        if (net != null) {
            if (lastRxBytes >= 0 && uptime > lastUptime && net[0] >= lastRxBytes) {
                jsonPut(data, "net_rx_kbs", round1((net[0] - lastRxBytes) / (double) KB / (uptime - lastUptime) * 1000));
                jsonPut(data, "net_tx_kbs", round1((net[1] - lastTxBytes) / (double) KB / (uptime - lastUptime) * 1000));
            }
        }

        long[] io = parseDiskstats(readFile("/proc/diskstats"));
        if (io != null) {
            if (lastRdSectors >= 0 && uptime > lastUptime && io[0] >= lastRdSectors) {
                // 扇区 512B → KB/s
                jsonPut(data, "io_rx_kbs", round1((io[0] - lastRdSectors) / 2.0 / (uptime - lastUptime) * 1000));
                jsonPut(data, "io_tx_kbs", round1((io[1] - lastWrSectors) / 2.0 / (uptime - lastUptime) * 1000));
            }
        }

        Double temp = readSysfsDouble("/sys/class/power_supply/battery/temp");
        if (temp != null) {
            jsonPut(data, "temp_c", round1(temp / 10));
        }
        Double batt = readSysfsDouble("/sys/class/power_supply/battery/capacity");
        if (batt != null) {
            jsonPut(data, "batt_pct", (int) (double) batt);
        }
        // GPU 占用:Mali 平台(Rockchip/三星等)暴露 utilisation 节点;其余平台缺省
        Double gpu = readSysfsDouble("/sys/class/misc/mali0/device/utilisation");
        if (gpu != null) {
            jsonPut(data, "gpu_util", (int) (double) gpu);
        }

        // 更新差分基准
        if (cpu != null) {
            lastCpuBusy = cpu[0];
            lastCpuTotal = cpu[1];
        }
        if (net != null) {
            lastRxBytes = net[0];
            lastTxBytes = net[1];
        }
        if (io != null) {
            lastRdSectors = io[0];
            lastWrSectors = io[1];
        }
        lastUptime = uptime;

        return data.length() == 0 ? null : data;
    }

    // ---- 以下解析函数为纯函数,单测覆盖(见 SysStatSamplerTest) ----

    // /proc/stat 首行 "cpu  u n s idle iowait irq softirq steal ..." → {busy, total}
    static long[] parseProcStat(String line) {
        if (line == null || !line.startsWith("cpu ")) {
            return null;
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5) {
            return null;
        }
        long idle = 0;
        long total = 0;
        for (int i = 1; i < parts.length; i++) {
            long v;
            try {
                v = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            total += v;
            if (i == 4 || i == 5) { // idle, iowait
                idle += v;
            }
        }
        return new long[]{total - idle, total};
    }

    // /proc/diskstats → 排除 loop/ram/zram/dm- 后的 {读扇区累计, 写扇区累计}
    // 行格式:major minor name reads ... sectorsRead(第6) ... writes ... sectorsWritten(第10)
    static long[] parseDiskstats(String content) {
        if (content == null) {
            return null;
        }
        long r = 0;
        long w = 0;
        boolean seen = false;
        for (String line : content.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 10 || f[2].matches("^(loop|ram|zram|dm-).*")) {
                continue;
            }
            try {
                r += Long.parseLong(f[5]);
                w += Long.parseLong(f[9]);
            } catch (NumberFormatException e) {
                continue;
            }
            seen = true;
        }
        return seen ? new long[]{r, w} : null;
    }

    // /proc/meminfo → {MemTotal_kb, MemAvailable_kb};MemAvailable 缺省(Android 4.3 以下)回退 MemFree
    static long[] parseMeminfo(String content) {
        long total = -1;
        long avail = -1;
        long free = -1;
        if (content == null) {
            return null;
        }
        for (String line : content.split("\n")) {
            if (line.startsWith("MemTotal:")) {
                total = parseKb(line);
            } else if (line.startsWith("MemAvailable:")) {
                avail = parseKb(line);
            } else if (line.startsWith("MemFree:")) {
                free = parseKb(line);
            }
        }
        if (total < 0) {
            return null;
        }
        return new long[]{total, avail >= 0 ? avail : Math.max(free, 0)};
    }

    // /proc/net/dev 全文 → 排除 lo 的 {rx_bytes, tx_bytes}
    static long[] parseNetDev(String content) {
        if (content == null) {
            return null;
        }
        long rx = 0;
        long tx = 0;
        boolean seen = false;
        for (String line : content.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String iface = line.substring(0, colon).trim();
            if (iface.isEmpty() || "lo".equals(iface)) {
                continue;
            }
            String[] fields = line.substring(colon + 1).trim().split("\\s+");
            if (fields.length < 9) {
                continue;
            }
            try {
                rx += Long.parseLong(fields[0]);
                tx += Long.parseLong(fields[8]);
            } catch (NumberFormatException e) {
                // 跳过该行
            }
            seen = true;
        }
        return seen ? new long[]{rx, tx} : null;
    }

    private static long parseKb(String line) {
        String[] parts = line.split("\\s+");
        try {
            return Long.parseLong(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // 读单数值 sysfs 节点(电池温度/电量、Mali GPU utilisation 等),失败返回 null
    private static Double readSysfsDouble(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line == null ? null : Double.valueOf(Double.parseDouble(line.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private long[] readProcStat() {
        return parseProcStat(readFirstLine("/proc/stat"));
    }

    private long[] readMeminfo() {
        return parseMeminfo(readFile("/proc/meminfo"));
    }

    private long[] readNetDev() {
        return parseNetDev(readFile("/proc/net/dev"));
    }

    private static String readFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFile(String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            return null;
        }
        return sb.toString();
    }

    private static void jsonPut(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
    }
}
