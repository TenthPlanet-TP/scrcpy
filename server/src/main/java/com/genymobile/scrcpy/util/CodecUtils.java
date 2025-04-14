package com.genymobile.scrcpy.util;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.os.Bundle;
import android.media.MediaCodec;

public final class CodecUtils {

    private CodecUtils() {
        // not instantiable
    }

    public static void setCodecOption(MediaFormat format, String key, Object value) {
        if (value instanceof Integer) {
            format.setInteger(key, (Integer) value);
        } else if (value instanceof Long) {
            format.setLong(key, (Long) value);
        } else if (value instanceof Float) {
            format.setFloat(key, (Float) value);
        } else if (value instanceof String) {
            format.setString(key, (String) value);
        }
    }

    public static void setCodecOption(MediaCodec codec, String key, Object value) {
        try {
            Bundle params = new Bundle();
            if (value instanceof Integer) {
                params.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                params.putLong(key, (Long) value);
            } else if (value instanceof Float) {
                params.putFloat(key, (Float) value);
            } else if (value instanceof String) {
                params.putString(key, (String) value);
            }
            codec.setParameters(params);
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public static MediaCodecInfo[] getEncoders(MediaCodecList codecs, String mimeType) {
        List<MediaCodecInfo> result = new ArrayList<>();
        for (MediaCodecInfo codecInfo : codecs.getCodecInfos()) {
            if (codecInfo.isEncoder() && Arrays.asList(codecInfo.getSupportedTypes()).contains(mimeType)) {
                result.add(codecInfo);
            }
        }
        return result.toArray(new MediaCodecInfo[result.size()]);
    }
}
