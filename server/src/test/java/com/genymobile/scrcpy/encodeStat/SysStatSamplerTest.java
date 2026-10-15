package com.genymobile.scrcpy.encodeStat;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SysStatSamplerTest {

    @Test
    public void testParseProcStat() {
        long[] r = SysStatSampler.parseProcStat("cpu  1234 56 789 9999 12 34 56 78");
        // total = 12258,busy = total - idle(9999) - iowait(12) = 2247
        assertArrayEquals(new long[]{2247, 12258}, r);
        // 不含 iowait 的老内核格式:total=13, busy=13-7=6
        assertArrayEquals(new long[]{6, 13}, SysStatSampler.parseProcStat("cpu  1 2 3 7"));
        assertNull(SysStatSampler.parseProcStat("cpu0 1 2 3 4"));
        assertNull(SysStatSampler.parseProcStat(null));
        assertNull(SysStatSampler.parseProcStat("cpu  1 2 3"));
    }

    @Test
    public void testParseMeminfo() {
        String content = "MemTotal:       7600000 kB\n"
                + "MemFree:         500000 kB\n"
                + "MemAvailable:   2400000 kB\n";
        assertArrayEquals(new long[]{7600000, 2400000}, SysStatSampler.parseMeminfo(content));
        // MemAvailable 缺失时回退 MemFree
        String old = "MemTotal: 100 kB\nMemFree: 40 kB\n";
        assertArrayEquals(new long[]{100, 40}, SysStatSampler.parseMeminfo(old));
        assertNull(SysStatSampler.parseMeminfo("MemFree: 1 kB\n"));
        assertNull(SysStatSampler.parseMeminfo(null));
    }

    @Test
    public void testParseNetDevExcludesLoopback() {
        String content = "Inter-|   Receive                                                |  Transmit\n"
                + " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets\n"
                + "  eth0: 1000    20    0    0    0     0          0         0  500     10\n"
                + "    lo: 999999  99    0    0    0     0          0         0  999999  99\n"
                + " wlan0: 200    5      0    0    0     0          0         0  300     8\n";
        assertArrayEquals(new long[]{1200, 800}, SysStatSampler.parseNetDev(content));
        assertNull(SysStatSampler.parseNetDev("no colon lines"));
        assertNull(SysStatSampler.parseNetDev(null));
    }

    @Test
    public void testParseDiskstatsExcludesVirtual() {
        String content = " 179       0 mmcblk0 123 5 2000 100 60 2 1000 50 0 0 0 0 0 0\n"
                + "   7       0 loop0 99 0 9999 0 99 0 9999 0 0 0 0 0 0 0\n"
                + " 254       0 zram0 50 0 500 0 50 0 500 0 0 0 0 0 0 0\n"
                + "   8       0 sda 10 0 100 10 20 0 400 20 0 0 0 0 0 0\n";
        // 只累计 mmcblk0 与 sda:读 2000+100,写 1000+400;loop/zram 排除
        assertArrayEquals(new long[]{2100, 1400}, SysStatSampler.parseDiskstats(content));
        assertNull(SysStatSampler.parseDiskstats(null));
        assertNull(SysStatSampler.parseDiskstats("nothing valid\n"));
    }

    @Test
    public void testSampleSmokeOnHostProc() {
        // 宿主机/CI 的 /proc 可读:sample 两次(建立差分基准),不抛异常且产出绝对量字段;
        // battery 在 JVM 侧缺文件,应被静默跳过
        SysStatSampler s = new SysStatSampler(null);
        s.sample(1000);
        JSONObject second = s.sample(2500);
        if (second != null && second.length() > 0) {
            assertEquals(true, second.has("mem_total_mb"));
        }
    }
}
