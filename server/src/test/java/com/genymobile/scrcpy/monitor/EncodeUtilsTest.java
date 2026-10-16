package com.genymobile.scrcpy.monitor;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

/**
 * frame 记录的序列化契约:与 example 前端(public/index.html)解析互为镜像,
 * 改字段名/类型须两端同步,本测试用于锁住契约。
 */
public class EncodeUtilsTest {

    private static JSONObject dataOf(String json) throws JSONException {
        JSONObject obj = new JSONObject(json);
        Assert.assertEquals("frame", obj.getString("type"));
        return obj.getJSONObject("data");
    }

    @Test
    public void testBuildFrameJsonFields() throws JSONException {
        String json = EncodeUtils.buildFrameJson(123456L, 40960, 35, 8L, false, 42L, 123464L);
        JSONObject data = dataOf(json);

        Assert.assertEquals(123456L, data.getLong("pts_ms"));
        Assert.assertEquals(40960, data.getInt("size"));
        Assert.assertEquals(35, data.getInt("qp"));
        Assert.assertEquals(8L, data.getLong("encode_cost_ms"));
        Assert.assertFalse(data.getBoolean("isKeyFrame"));
        Assert.assertEquals(42L, data.getLong("seq"));
        Assert.assertEquals(123464L, data.getLong("ts_ms"));
    }

    @Test
    public void testBuildFrameJsonKeyFrameAndMissingQp() throws JSONException {
        // qp 无 so 时为 -1;首帧 pts=0、encode_cost=0
        String json = EncodeUtils.buildFrameJson(0L, 512, -1, 0L, true, 0L, 100L);
        JSONObject data = dataOf(json);

        Assert.assertTrue(data.getBoolean("isKeyFrame"));
        Assert.assertEquals(-1, data.getInt("qp"));
        Assert.assertEquals(0L, data.getLong("pts_ms"));
        Assert.assertEquals(0L, data.getLong("encode_cost_ms"));
    }

    @Test
    public void testBuildFrameJsonSeqMonotonic() throws JSONException {
        // 调用方递增 seq;序列化本身应原样输出,不丢精度(2^33 以上仍精确)
        long bigSeq = 8589934593L; // 2^33 + 1
        String json = EncodeUtils.buildFrameJson(1L, 1, 1, 1L, false, bigSeq, 2L);
        Assert.assertEquals(bigSeq, dataOf(json).getLong("seq"));
    }
}
