package com.example.myapplication.perf;

import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.metrics.performance.PerformanceMetricsState;
import androidx.tracing.Trace;

/**
 * Perfetto / systrace 标记与启动耗时打点。
 *
 * <p>Matrix 的启动画像给出总时长，这里的 trace section 负责在 Perfetto 时间轴上回答
 * "这段时间里主线程在做什么"：IO、锁竞争、布局解析会被摊开成可见的条块。
 */
public final class PerfMarkers {

    private static final String TAG = "PerfMarkers";

    private static final long PROCESS_START = SystemClock.elapsedRealtime();
    private static long sAppCreateStart;
    private static long sFirstFrameDone;

    private PerfMarkers() {
    }

    /** 在 Application.onCreate 入口调用。 */
    public static void markAppCreateStart() {
        sAppCreateStart = SystemClock.elapsedRealtime();
    }

    /** 在首个 Activity 首帧绘制完成时调用。 */
    public static void markFirstFrame(@NonNull Object decorView) {
        if (sFirstFrameDone != 0L) {
            return;
        }
        sFirstFrameDone = SystemClock.elapsedRealtime();
        long appCreate = sAppCreateStart > 0 ? sFirstFrameDone - sAppCreateStart : -1;
        Log.i(TAG, "startup: processStart->firstFrame="
                + (sFirstFrameDone - PROCESS_START) + "ms appCreatePhase=" + appCreate + "ms");
    }

    public static long elapsedSinceProcessStart() {
        return SystemClock.elapsedRealtime() - PROCESS_START;
    }

    /** 用法：try (PerfMarkers.Scope s = PerfMarkers.section("inflate-home")) { ... } */
    @NonNull
    public static Scope section(@NonNull String name) {
        return new Scope(name);
    }

    /** 把当前调用栈写进 trace，便于在 Perfetto 里定位具体代码位置。 */
    public static void stackHint(@NonNull String prefix) {
        if (!isTracingEnabled()) {
            return;
        }
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder(prefix).append(" <- ");
        for (int i = 1; i < Math.min(st.length, 5); i++) {
            sb.append(st[i].getMethodName()).append(':').append(st[i].getLineNumber()).append(' ');
        }
        Trace.beginSection(sb.toString());
        Trace.endSection();
    }

    public static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    public static boolean isTracingEnabled() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && Trace.isEnabled();
    }

    /** 给 JankStats 附带上下文，掉帧记录里能看到"当时在做什么业务"。 */
    public static void labelJankContext(@NonNull Object viewHierarchyRoot,
                                        @NonNull String key,
                                        @NonNull String value) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            PerformanceMetricsState.getHolderForHierarchy((android.view.View) viewHierarchyRoot)
                    .getState()
                    .putState(key, value);
        } catch (Throwable ignored) {
            // metrics-state 在高版本才有实现，失败不影响业务
        }
    }

    /** AutoCloseable 的 trace section，配合 try-with-resources 使用。 */
    public static final class Scope implements AutoCloseable {

        private final String mName;

        private Scope(String name) {
            mName = name;
            if (isTracingEnabled()) {
                Trace.beginSection(mName);
            }
        }

        @Override
        public void close() {
            if (isTracingEnabled()) {
                Trace.endSection();
            }
        }
    }
}
