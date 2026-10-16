package com.genymobile.scrcpy.monitor;

import com.genymobile.scrcpy.util.Ln;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.io.IOException;

// import com.webrtc.utils.ToolKits;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;

import android.os.FileObserver;
import com.genymobile.scrcpy.model.CodecOption;
import java.util.List;


public class EncodeUtils {
    private long seq = 0L;
    private long prev_time = 0L;
    private int fbd = 0;
    private int prev_fbd = 0;
    private int bytes_generated = 0;
    private ToolKits toolKits = new ToolKits();
    private long mHandle = -1;

    private volatile OnParseCb onParseCb;

    private SingleFileObserver mSingleFileObserver = null;
    private static final String LISTEN_DIR = "/data/local/tmp/";
    private static final String LISTEN_FILE = "/data/local/tmp/scrcpy.json";

    private volatile int mBitRateKbps = -1;
    private volatile int mMinQp = -1;
    private volatile int mMaxQp = -1;
    private List<CodecOption> mCodecOptions;

    private WorkTask mExecutor = new WorkTask("EncodeUtils");

    public EncodeUtils() {

    }

    public boolean init(String mimeType) {
        Ln.i("create bitstream parser, codec: " + mimeType);
        if (ToolKits.isNativeAvailable()) {
            if (mimeType.equals("video/hevc")) {
                mHandle = toolKits.nativeCreateBitstreamParser(ToolKits.kVideoCodecH265);
            } else if (mimeType.equals("video/avc")) {
                mHandle = toolKits.nativeCreateBitstreamParser(ToolKits.kVideoCodecH264);
            } else {
                Ln.e("unsupported codec type: " + mimeType);
                return false;
            }
        } else {
            // native bitstream parser unavailable: keep frame stats (qp=-1), never break mirroring
            Ln.w("libwebrtcLibsJni.so unavailable, qp stats disabled");
            mHandle = -1;
        }

        // mSingleFileObserver = new SingleFileObserver(new File(LISTEN_DIR), FileObserver.MODIFY);
        mSingleFileObserver = new SingleFileObserver(new File(LISTEN_DIR));
        mSingleFileObserver.setCallback(new OnEventCallbackImpl());
        mSingleFileObserver.startWatching();

        return true;
    }

    public void setCallback(OnParseCb cb) {
        onParseCb = cb;
    }

    public synchronized void exit() {
        Ln.i("release bitstream parser");
        if (mHandle != -1) {
            toolKits.nativeReleaseBitstreamParser(mHandle);
            mHandle = -1;
        }

        if (mSingleFileObserver != null) {
            mSingleFileObserver.stopWatching();
        }

        if (mExecutor != null) {
            mExecutor.release();
        }
    }


    public synchronized void parseEncodeStream(ByteBuffer codecBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        if (onParseCb == null) {
            // no consumer for encode stats, skip per-frame work
            return;
        }


        long deltaMs = 0;
        long current_time = SystemClock.uptimeMillis();
        long pts = bufferInfo.presentationTimeUs / 1000;
        if (bufferInfo.presentationTimeUs > 0) {
            deltaMs = current_time - pts;
            // Ln.d("encode cost(ms): " + deltaMs);
        }
        // Ln.d("bufferInfo size: " + bufferInfo.size + " pts: " + bufferInfo.presentationTimeUs + " flags: " + bufferInfo.flags + " offset: " + bufferInfo.offset + " encode_cost_ms: " + deltaMs);
        // Ln.d("bufferInfo flags: " + bufferInfo.flags + " offset: " + bufferInfo.offset + " size: " + bufferInfo.size + " encode_cost_ms: " + deltaMs);

        boolean isKeyFrame = false;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // Ln.d("codec config");
        } else if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            // Ln.d("key frame");
            isKeyFrame = true;
        }

        // 统计编码码率和帧率
        // static long prev_time = 0L;
        // static int fbd = 0;
        // static int prev_fbd = 0;
        // static int bytes_generated = 0;

        fbd++;
        bytes_generated += bufferInfo.size;

        if (current_time < prev_time) {
            prev_time = current_time;
            bytes_generated = 0;
            prev_fbd = fbd;
        } else if (current_time - prev_time >= 1000) {
            int num_fbd = fbd - prev_fbd;
            long time_diff = current_time - prev_time;
            if (prev_time > 0 && num_fbd > 0 && time_diff > 0) {
                float framerate = (num_fbd * 1000) / (float)time_diff;
                float bitrateKbps = (bytes_generated * 8 / (float)num_fbd) * framerate / 1000;
                // Ln.d("stats: avg. fps: " + framerate + ", bitrateKbps: " + bitrateKbps);
                // Ln.d(String.format("stats: avg. fps: %.1f, bitrateKbps: %.1f", framerate, bitrateKbps));

                JSONObject json = new JSONObject();
                jsonPut(json, "type", "frameStat");
                JSONObject data = new JSONObject();
                jsonPut(data, "framerate", (int)framerate);
                jsonPut(data, "bitrateKbps", (int)bitrateKbps);
                jsonPut(json, "data", data);
        
                if (onParseCb != null) {
                    onParseCb.callback(json.toString());
                }
            }
            prev_time = current_time;
            bytes_generated = 0;
            prev_fbd = fbd;
        }

        int ret = -1;
        if (mHandle != -1) {
            ret = toolKits.nativeParseBitstream(mHandle, codecBuffer);
            if (ret < 0) {
                Ln.e("parse bitstream failed: " + ret);
            } else {
                ret = toolKits.nativeGetLastSliceQp(mHandle);
            }
        }

        JSONObject json = new JSONObject();
        jsonPut(json, "type", "frame");
        JSONObject data = new JSONObject();
        jsonPut(data, "pts_ms", pts);
        jsonPut(data, "size", bufferInfo.size);
        jsonPut(data, "qp", ret);
        jsonPut(data, "encode_cost_ms", deltaMs);
        jsonPut(data, "isKeyFrame", isKeyFrame);
        jsonPut(data, "seq", seq);
        jsonPut(data, "ts_ms", current_time);
        jsonPut(json, "data", data);
        seq++;

        if (onParseCb != null) {
            onParseCb.callback(json.toString());
        }
    }

    // 协议契约:frame 记录字段与 example 前端(public/index.html)解析互为镜像,
    // 改字段须两端同步;独立静态方法便于单测(不依赖 android 运行时)
    static String buildFrameJson(long ptsMs, int size, int qp, long encodeCostMs, boolean isKeyFrame, long seq, long tsMs) {
        JSONObject json = new JSONObject();
        jsonPut(json, "type", "frame");
        JSONObject data = new JSONObject();
        jsonPut(data, "pts_ms", ptsMs);
        jsonPut(data, "size", size);
        jsonPut(data, "qp", qp);
        jsonPut(data, "encode_cost_ms", encodeCostMs);
        jsonPut(data, "isKeyFrame", isKeyFrame);
        jsonPut(data, "seq", seq);
        jsonPut(data, "ts_ms", tsMs);
        jsonPut(json, "data", data);
        return json.toString();
    }

    private static void jsonPut(JSONObject json, String key, Object value) {
        try {
          json.put(key, value);
        } catch (JSONException e) {
          throw new RuntimeException(e);
        }
    }

    public interface OnParseCb {
        void callback(String str);
    }

    public int getEncodeBitRate() {
        return mBitRateKbps;
    }

    public SimpleEntry<Integer, Integer> getQpRange() {
        return new AbstractMap.SimpleEntry<>(mMinQp, mMaxQp);
    }

    public synchronized List<CodecOption> drainCodecOptions() {
        List<CodecOption> codecOptions = mCodecOptions;
        mCodecOptions = null;
        return codecOptions;
    }

    public class OnEventCallbackImpl implements SingleFileObserver.OnEventCallback {
        @Override
        public void onNotify(String path) {
            // mExecutor.executeTask(() -> {
                // Ln.d("Path: " + path);
                parseProtocol(path);
            // });
        }
    }

    private String readFileAsString(String filePath) {
        for (int i = 0; i < 5; i++) {
            File file = new File(filePath);
            // 检查文件是否存在以及是否为空
            if (!file.exists()) {
                Ln.e("file is not exist");
                return null;
            }

            if (file.length() == 0) {
                Ln.e("file is empty");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }

            StringBuilder contentBuilder = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String currentLine;
                while ((currentLine = br.readLine()) != null) {
                    contentBuilder.append(currentLine);
                }
            } catch (IOException e) {
                Ln.e("readFileAsString failed: " + e);
                return null;
            }
            return contentBuilder.toString();
        }
        return "";
    }
    
    public void parseProtocol(String path) {
        mExecutor.executeTask(() -> {
            if (path == null || path.isEmpty()) {
                Ln.e("error, input path is null");
                return;
            } else if (!path.equals(LISTEN_FILE)) {
                // Ln.e("error, [" + LISTEN_FILE + "] + mismatch [" + path + "]");
                return;
            }

            String content = readFileAsString(path);
            if (content == null || content.isEmpty()) {
                Ln.e("readFileAsString failed: content is null");
                return;
            }

            JSONObject jsonObject;
            try {
                jsonObject = new JSONObject(content);
                Ln.d("json: " + jsonObject.toString());

                /**
                 * echo "{\"data\":{\"bitrateKbps\":1000, \"qp_min\":10, \"qp_max\":48},\"type\":\"encodeControl\"}" > /data/local/tmp/scrcpy.json
                 * echo "{\"data\":{\"qp_min\":10, \"qp_max\":48},\"type\":\"encodeControl\"}" > /data/local/tmp/scrcpy.json
                 * echo "{\"data\":{\"bitrateKbps\":1000},\"type\":\"encodeControl\"}" > /data/local/tmp/scrcpy.json
                 * echo "{\"data\":{\"video-codec-options\":\"video-qp-i-min:int=10,video-qp-i-max:int=48,video-qp-q-min:int=10,video-qp-p-max:int=48,video-bitrate:int=1000000\"},\"type\":\"encodeControl\"}" > /data/local/tmp/scrcpy.json
                 */
                if (jsonObject.has("type")) {
                    try {
                        String type = jsonObject.getString("type");
                        if (type.equals("encodeControl")) {
                            JSONObject data = jsonObject.getJSONObject("data");
                            if (data.has("video-codec-options")) {
                                String options = data.getString("video-codec-options");
                                // Ln.d("set codec options: " + options);
                                List<CodecOption> codecOptions = CodecOption.parse(options);
                                // for (CodecOption option : codecOptions) {
                                //     Ln.d("key: " + option.getKey() + ", value: " + option.getValue());
                                // }
                                synchronized(this) {
                                    mCodecOptions = codecOptions;
                                }
                            } else {
                                if (data.has("bitrateKbps")) {
                                    try {
                                        int bitrateKbps = data.getInt("bitrateKbps");
                                        if (bitrateKbps > 0) {
                                            Ln.d("set bitrateKbps: " + bitrateKbps);
                                            mBitRateKbps = bitrateKbps * 1000;
                                        }
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                        return;
                                    }
                                }
                                if (data.has("qp_min") && data.has("qp_max")) {
                                    try {
                                        mMinQp = data.getInt("qp_min");
                                        mMaxQp = data.getInt("qp_max");
                                        Ln.d("set qp range: [" + mMinQp + ", " + mMaxQp + "]");
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                        return;
                                    }
                                }
                            }
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                        return;
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }
        });
    }
}
