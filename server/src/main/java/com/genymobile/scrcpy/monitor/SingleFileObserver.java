package com.genymobile.scrcpy.monitor;

import android.os.FileObserver;
// import android.util.Ln;
import com.genymobile.scrcpy.util.Ln;

// import androidx.annotation.NonNull;
// import androidx.annotation.Nullable;

import java.io.File;

public class SingleFileObserver extends FileObserver {
    // private static final String TAG = "SingleFileObserver";

    protected File mFile;
    protected int mMask;
    public static final int DEFAULT_MASK = CREATE | MODIFY | DELETE;

    private OnEventCallback mCallback = null;

    private long lastModifiedTime = 0;
    private static final int DEBOUNCE_TIME = 20; // 去抖时间，单位毫秒

    public SingleFileObserver(File file) {
        this(file, DEFAULT_MASK);
    }

    public SingleFileObserver(File file, int mask) {
        super(file.getPath(), mask);
        mFile = file;
        mMask = mask;
        Ln.d("listen dir: " + file.getAbsolutePath());
    }

    public void setCallback(OnEventCallback cb) {
        mCallback = cb;
    }

    /*
     * 如果遇到 FileObserver 不回调，其中有一种可能性就是监听的文件不存在。在调用 startWatching()
     * 之前，一定要保证这个文件已经存在。
     */
    @Override
    public void onEvent(int i, String s) {
        String path = mFile.getAbsolutePath() + File.separator + s;
        Ln.d("Event: " + eventToString(i) + ", Path: " + path);

        int event = i & FileObserver.ALL_EVENTS;
        switch (event) {
            case FileObserver.CREATE:
                break;
            case FileObserver.MODIFY:
                // 去重处理。因为我发现 Java 的 FileObserver 监控指定目录，当往该目录修改一个文件时，onEvent 会回调两次 MODIFY
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastModifiedTime > DEBOUNCE_TIME) {
                    lastModifiedTime = currentTime;
                    if (mCallback != null) {
                        mCallback.onNotify(path);
                    }
                } else {
                    Ln.w("Filter for second repeat events");
                }
                break;
            case FileObserver.DELETE:
                break;
        }
    }

    private static String eventToString(int event) {
        switch (event) {
            case FileObserver.ACCESS:
                return "ACCESS";
            case FileObserver.MODIFY:
                return "MODIFY";
            case FileObserver.ATTRIB:
                return "ATTRIB";
            case FileObserver.CLOSE_WRITE:
                return "CLOSE_WRITE";
            case FileObserver.CLOSE_NOWRITE:
                return "CLOSE_NOWRITE";
            case FileObserver.OPEN:
                return "OPEN";
            case FileObserver.MOVED_FROM:
                return "MOVED_FROM";
            case FileObserver.MOVED_TO:
                return "MOVED_TO";
            case FileObserver.CREATE:
                return "CREATE";
            case FileObserver.DELETE:
                return "DELETE";
            case FileObserver.DELETE_SELF:
                return "DELETE_SELF";
            case FileObserver.MOVE_SELF:
                return "MOVE_SELF";
            default:
                return "UNKNOWN";
        }
    }

    public interface OnEventCallback {
        void onNotify(String str);
    }
}
