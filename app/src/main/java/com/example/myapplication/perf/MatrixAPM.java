package com.example.myapplication.perf;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.metrics.performance.JankStats;

import com.tencent.matrix.Matrix;
import com.tencent.matrix.batterycanary.BatteryMonitorPlugin;
import com.tencent.matrix.batterycanary.monitor.BatteryMonitorConfig;
import com.tencent.matrix.iocanary.IOCanaryPlugin;
import com.tencent.matrix.iocanary.config.IOConfig;
import com.tencent.matrix.memory.canary.MemoryCanaryConfig;
import com.tencent.matrix.memory.canary.MemoryCanaryPlugin;
import com.tencent.matrix.plugin.Plugin;
import com.tencent.matrix.plugin.PluginListener;
import com.tencent.matrix.report.Issue;
import com.tencent.matrix.resource.ResourcePlugin;
import com.tencent.matrix.resource.config.ResourceConfig;
import com.tencent.matrix.trace.TracePlugin;
import com.tencent.matrix.trace.config.TraceConfig;

import java.io.File;

/**
 * Tencent Matrix APM 接入（免插桩子集）。
 *
 * <p>约束：matrix-gradle-plugin:2.1.0 仍依赖 AGP 的 Transform API（AGP 8.0 已删除），
 * 因此本工程 AGP 9.1.1 这条栈不能做字节码注入。能力影响如下：
 * <ul>
 *   <li>可用（纯运行时）：SignalAnrTracer 信号式主线程堆栈、LooperMonitor 消息分帧、
 *       FPS/Jank、启动耗时、IOCanary、MemoryCanary、BatteryCanary、Activity 泄漏看护</li>
 *   <li>失效（需注入）：AppMethodBeat 方法级耗时、EvilMethodTrace、ResourceCanary 的
 *       broadcast/handler/msg 钩子 —— 故这里显式关闭</li>
 * </ul>
 */
public final class MatrixAPM {

    private static final String TAG = "MatrixAPM";

    private static volatile boolean sInstalled;

    private MatrixAPM() {
    }

    public static void init(@NonNull Application app, boolean debug) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;

        File anrDir = new File(app.getFilesDir(), "matrix/anr");
        //noinspection ResultOfMethodCallIgnored
        anrDir.mkdirs();

        TraceConfig traceConfig = new TraceConfig.Builder()
                .dynamicConfig(null)
                .enableAppMethodBeat(false)          // 需要插件注入方法出入口，此处关闭
                .enableEvilMethodTrace(false)      // 同上
                .enableAnrTrace(true)
                .enableSignalAnrTrace(true)        // 信号路径：不依赖插桩，抓主线程实时堆栈
                .enableFPS(true)
                .enableStartup(true)               // 启动耗时画像
                .enableIdleHandlerTrace(debug)
                .isDebug(debug)
                .isDevEnv(debug)
                .anrTracePath(anrDir.getAbsolutePath())
                .printTracePath(anrDir.getAbsolutePath())
                .build();

        IOConfig ioConfig = new IOConfig.Builder()
                .dynamicConfig(null)
                .build();

        // NO_DUMP：把 heap dump 让给 LeakCanary，避免两个工具同时抓堆导致卡顿与 OOM
        ResourceConfig resourceConfig = new ResourceConfig.Builder()
                .dynamicConfig(null)
                .setAutoDumpHprofMode(ResourceConfig.DumpMode.NO_DUMP)
                .setDetectDebuger(false)
                .build();

        BatteryMonitorConfig batteryConfig = new BatteryMonitorConfig.Builder()
                .enableBackgroundMode(true)
                .enableForegroundMode(true)
                .build();

        Matrix matrix = new Matrix.Builder(app)
                .plugin(new TracePlugin(traceConfig))
                .plugin(new IOCanaryPlugin(ioConfig))
                .plugin(new MemoryCanaryPlugin(new MemoryCanaryConfig()))
                .plugin(new BatteryMonitorPlugin(batteryConfig))
                .plugin(new ResourcePlugin(resourceConfig))
                .pluginListener(new LogReporter())
                .build();

        Matrix.init(matrix);
        matrix.startAllPlugins();

        attachJankStats(app, debug);

        Log.i(TAG, "Matrix installed, plugins=" + matrix.getPlugins().size()
                + " anrDir=" + anrDir.getAbsolutePath());
    }

    /** Matrix 各插件的运行状态与检测结果，全部经 PluginListener 回流。 */
    private static final class LogReporter implements PluginListener {

        @Override
        public void onInit(@Nullable Plugin plugin) {
            Log.i(TAG, "plugin init   : " + tag(plugin));
        }

        @Override
        public void onStart(@Nullable Plugin plugin) {
            Log.i(TAG, "plugin start  : " + tag(plugin));
        }

        @Override
        public void onStop(@Nullable Plugin plugin) {
            Log.i(TAG, "plugin stop   : " + tag(plugin));
        }

        @Override
        public void onDestroy(@Nullable Plugin plugin) {
            Log.i(TAG, "plugin destroy: " + tag(plugin));
        }

        @Override
        public void onReportIssue(@Nullable Issue issue) {
            if (issue == null) {
                return;
            }
            // Issue 的内容是 JSONObject：type/tag/process/time + 堆栈等扩展键
            Log.w(TAG, "issue [" + issue.getContent().optString(Issue.ISSUE_REPORT_TAG) + "] "
                    + issue.getContent().optString(Issue.ISSUE_REPORT_TYPE));
            Log.w(TAG, issue.toString());
        }

        private static String tag(@Nullable Plugin plugin) {
            return plugin == null ? "?" : plugin.getTag();
        }
    }

    // ------------------------------------------------------------------
    // 掉帧看板替代件：androidx.metrics 的 JankStats（AndroidGodEye 已停更且 jitpack 返回 401）
    // ------------------------------------------------------------------

    private static void attachJankStats(Application app, final boolean debug) {
        app.registerActivityLifecycleCallbacks(new SimpleActivityLifecycleCallbacks() {

            private final android.util.ArrayMap<Activity, JankStats> mTracked =
                    new android.util.ArrayMap<>();

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (activity.getWindow() == null) {
                    return;
                }
                try {
                    JankStats stats = JankStats.createAndTrack(activity.getWindow(), frameData -> {
                        if (!debug || frameData == null || !frameData.isJank()) {
                            return;
                        }
                        Log.w(TAG, "JANK frame "
                                + (frameData.getFrameDurationUiNanos() / 1_000_000L) + "ms states="
                                + frameData.getStates());
                    });
                    mTracked.put(activity, stats);
                } catch (Throwable t) {
                    Log.w(TAG, "JankStats unavailable: " + t.getMessage());
                }
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                JankStats stats = mTracked.remove(activity);
                if (stats != null) {
                    // JankStats 没有 stopTracking()，只有 Kotlin 属性 trackingEnabled
                    stats.setTrackingEnabled(false);
                }
            }
        });
    }

    /** ActivityLifecycleCallbacks 的空实现骨架，只覆写需要的回调。 */
    private abstract static class SimpleActivityLifecycleCallbacks
            implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle s) {}
        @Override public void onActivityStarted(@NonNull Activity a) {}
        @Override public void onActivityResumed(@NonNull Activity a) {}
        @Override public void onActivityPaused(@NonNull Activity a) {}
        @Override public void onActivityStopped(@NonNull Activity a) {}
        @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle s) {}
        @Override public void onActivityDestroyed(@NonNull Activity a) {}
    }
}
