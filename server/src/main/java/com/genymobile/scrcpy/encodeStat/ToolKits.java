package com.genymobile.scrcpy.encodeStat;

import java.nio.ByteBuffer;

import com.genymobile.scrcpy.util.Ln;


public class ToolKits {
    public static final int kVideoCodecH264 = 4;
    public static final int kVideoCodecH265 = 6;

    private static boolean nativeAvailable;

    static {
        // System.loadLibrary("webrtcLibsJni");
        try {
            System.load("/data/local/tmp/libwebrtcLibsJni.so");
            nativeAvailable = true;
        } catch (UnsatisfiedLinkError e) {
            // the bitstream parser is optional (qp stats only), never break mirroring
            Ln.w("libwebrtcLibsJni.so unavailable, qp stats disabled: " + e);
            nativeAvailable = false;
        }
    }

    static boolean isNativeAvailable() {
        return nativeAvailable;
    }

    /*
    public enum VideoCodecType {
        // There are various memset(..., 0, ...) calls in the code that rely on
        // kVideoCodecGeneric being zero.
        kVideoCodecGeneric,
        kVideoCodecVP8,
        kVideoCodecVP9,
        kVideoCodecAV1,
        kVideoCodecH264,
        kVideoCodecMultiplex,
        kVideoCodecH265,
    };
    */

    public native long nativeCreateBitstreamParser(int videoCodecType);

    public native int nativeReleaseBitstreamParser(long handle);

    public native int nativeParseBitstream(long handle, ByteBuffer buffer);

    public native int nativeGetLastSliceQp(long handle);

}
