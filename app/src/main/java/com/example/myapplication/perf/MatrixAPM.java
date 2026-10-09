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
import com.tencent.mrs.plugin.IDynamicConfig;

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
 *
 * <p>还有一个 ABI 维度的坑：Matrix 2.1.0 的 native 只给 arm64-v8a / armeabi-v7a 发
 * {@code libtrace-canary.so} 与 {@code libwechatbacktrace.so}，所以 x86/x86_64（官方模拟器、
 * x86 车机）上 TraceCanary 与 ResourceCanary 根本没有 so 可用。下面按当前 ABI 探测后再决定装载哪些插件，
 * 缺库就跳过并打日志，绝不让 APM 把宿主进程掀掉（2026-10-09 在 API 34 x86_64 模拟器实测有效）。
 */
public final class MatrixAPM {

    private static final String TAG = "MatrixAPM";

    private static volatile boolean sInstalled;

    /**
     * 各 Config 的 dynamicConfig 不能传 null：插件 start() 里会无条件调用它读开关
     * （实测 IOCanaryCore.initDetectorsAndHookers 直接 NPE，把 startAllPlugins 打断，
     * 后面的 MemoryCanary / Battery 全都没起来）。这里给一个恒返回默认值的实现，
     * 等价于"不做云端动态开关，全部按 TraceConfig/IOConfig 里的静态值跑"。
     */
    private static final IDynamicConfig DYNAMIC_CONFIG = new IDynamicConfig() {
        @Override public String get(String key, String defValue) { return defValue; }
        @Override public int get(String key, int defValue) { return defValue; }
        @Override public long get(String key, long defValue) { return defValue; }
        @Override public boolean get(String key, boolean defValue) { return defValue; }
        @Override public float get(String key, float defValue) { return defValue; }
    };

    private MatrixAPM() {
    }

    public static void init(@NonNull Application app, boolean debug) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;

        // Matrix 的 native 库只发 arm64-v8a / armeabi-v7a：libtrace-canary.so（卡顿/ANR 的 native 侧）、
        // libwechatbacktrace.so（栈回溯，matrix-backtrace 与 resource-canary 都靠它）；
        // 只有 libio-canary.so / libmatrix_hprof_analyzer.so / libmatrix_mem_util.so 覆盖到 x86 与 x86_64。
        // 缺 so 时插件在 Plugin.init() 里抛 UnsatisfiedLinkError，会顺着 Application.onCreate 掀掉整个进程，
        // 所以装载前先按当前 ABI 实测一次，别让 APM 把宿主搞崩。
        final boolean hasTraceCanary = hasNativeLib(app, "libtrace-canary.so");
        final boolean hasBacktrace = hasNativeLib(app, "libwechatbacktrace.so");
        final boolean hasHprofAnalyzer = hasNativeLib(app, "libmatrix_hprof_analyzer.so");

        File anrDir = new File(app.getFilesDir(), "matrix/anr");
        //noinspection ResultOfMethodCallIgnored
        anrDir.mkdirs();

        TraceConfig traceConfig = new TraceConfig.Builder()
                .dynamicConfig(DYNAMIC_CONFIG)
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
                .dynamicConfig(DYNAMIC_CONFIG)
                .build();

        // NO_DUMP：把 heap dump 让给 LeakCanary，避免两个工具同时抓堆导致卡顿与 OOM
        ResourceConfig resourceConfig = new ResourceConfig.Builder()
                .dynamicConfig(DYNAMIC_CONFIG)
                .setAutoDumpHprofMode(ResourceConfig.DumpMode.NO_DUMP)
                .setDetectDebuger(false)
                .build();

        BatteryMonitorConfig batteryConfig = new BatteryMonitorConfig.Builder()
                .enableBackgroundMode(true)
                .enableForegroundMode(true)
                .build();

        Matrix.Builder builder = new Matrix.Builder(app);
        if (hasTraceCanary) {
            builder.plugin(new TracePlugin(traceConfig));
        } else {
            Log.w(TAG, "TracePlugin skipped: libtrace-canary.so absent in "
                    + app.getApplicationInfo().nativeLibraryDir + " (ANR/FPS/Startup 无 native 支撑)");
        }
        builder.plugin(new IOCanaryPlugin(ioConfig));
        builder.plugin(new MemoryCanaryPlugin(new MemoryCanaryConfig()));
        builder.plugin(new BatteryMonitorPlugin(batteryConfig));
        if (hasBacktrace && hasHprofAnalyzer) {
            builder.plugin(new ResourcePlugin(resourceConfig));
        } else {
            Log.w(TAG, "ResourcePlugin skipped: libwechatbacktrace.so / libmatrix_hprof_analyzer.so 不全");
        }
        builder.pluginListener(new LogReporter());
        Matrix matrix = builder.build();

        try {
            Matrix.init(matrix);
            matrix.startAllPlugins();
        } catch (Throwable t) {
            // 兜底：任何一个插件起不来，只降级掉 APM，不能影响 App 本身
            Log.e(TAG, "Matrix startup failed, APM degraded", t);
        }

        attachJankStats(app, debug);

        Log.i(TAG, "Matrix installed, plugins=" + matrix.getPlugins().size()
                + " anrDir=" + anrDir.getAbsolutePath());
    }

    /** 当前 ABI 到底有没有打包这个 so：split-apk、abiFilters、非 ARM 车机/模拟器都走这里判定。 */
    private static boolean hasNativeLib(@NonNull Application app, @NonNull String fileName) {
        String dir = app.getApplicationInfo().nativeLibraryDir;
        return dir != null && new File(dir, fileName).isFile();
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
    // 掉帧统计：androidx.metrics 的 JankStats（AndroidGodEye 看板见 src/debug 下的 GodEyeDashboard）
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
