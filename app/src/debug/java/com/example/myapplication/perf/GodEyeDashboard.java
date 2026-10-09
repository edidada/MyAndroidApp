package com.example.myapplication.perf;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;

import cn.hikyson.godeye.core.GodEye;
import cn.hikyson.godeye.core.GodEyeConfig;
import cn.hikyson.godeye.core.GodEyeHelper;
import cn.hikyson.godeye.core.internal.modules.appsize.AppSizeConfig;
import cn.hikyson.godeye.core.internal.modules.battery.BatteryConfig;
import cn.hikyson.godeye.core.internal.modules.cpu.CpuConfig;
import cn.hikyson.godeye.core.internal.modules.crash.CrashConfig;
import cn.hikyson.godeye.core.internal.modules.fps.FpsConfig;
import cn.hikyson.godeye.core.internal.modules.memory.HeapConfig;
import cn.hikyson.godeye.core.internal.modules.memory.PssConfig;
import cn.hikyson.godeye.core.internal.modules.memory.RamConfig;
import cn.hikyson.godeye.core.internal.modules.pageload.PageloadConfig;
import cn.hikyson.godeye.core.internal.modules.sm.SmConfig;
import cn.hikyson.godeye.core.internal.modules.startup.StartupConfig;
import cn.hikyson.godeye.core.internal.modules.thread.ThreadConfig;
import cn.hikyson.godeye.core.internal.modules.traffic.TrafficConfig;
import cn.hikyson.godeye.core.internal.modules.viewcanary.ViewCanaryConfig;

/**
 * AndroidGodEye 实时看板（debug 变体专用，release 的同名空实现在 app/src/releaseStub/java）。
 *
 * <p>它和 Matrix 的分工：Matrix 负责"事后取证"（ANR 现场栈、IO 归因、启动画像落盘上报），
 * AndroidGodEye 负责"当场看数"——在 PC 浏览器里连到设备端口，实时看 FPS / CPU / 内存 /
 * 电量 / 线程 / 启动 / 页面加载 / 视图层级，用来建立车机功耗与稳定性的数据感。
 *
 * <p>看板页面由 godeye-monitor 的 assets（android-godeye-dashboard）内置，
 * 通过 {@link GodEyeHelper#startMonitor()} 起本地 http+ws 服务。
 * <b>设备侧监听端口是 5390</b>（实测日志：{@code AndroidGodEye monitor is running at port [5390]}），
 * 不是 README 里常见的 8087。PC 侧：
 * <pre>
 * adb forward tcp:5390 tcp:5390
 * 浏览器打开 http://localhost:5390/index.html   # 根路径 / 返回 404，必须带 /index.html
 * </pre>
 * 2026-10-09 在 API 34 x86_64 模拟器实测：FPS / RAM / PSS / CPU / Heap / Traffic / Battery /
 * AppSize / Thread / PageLoad / Block(卡顿) 全部实时刷新。
 *
 * <p>SM 模块（{@code SmConfig}）会 {@code startForegroundService} 拉起它自带的
 * {@code LocalNotificationListenerService}，而该库的 manifest（2020 年，targetSdk 29）没声明前台服务类型，
 * targetSdk 34+ 上会抛 {@code MissingForegroundServiceTypeException} 直接崩进程。
 * 修法在 {@code app/src/debug/AndroidManifest.xml} 里给这个 service 合并补声明，见那里。
 * SM 顺带提供主线程长卡顿探测（实测报出 718ms / 598ms Jank），是 x86 设备上 Matrix TraceCanary
 * 缺 native 库时的临时替代。
 *
 * <p>显式关闭的模块（不是漏掉）：
 * <ul>
 *   <li>LEAK —— 需要 godeye-leakcanary（基于旧版 LeakCanary API），与本工程 LeakCanary 2.14 冲突</li>
 *   <li>METHOD —— MethodCanary 要字节码插桩，AGP 9 上装不了插件，开了只会拿到空数据</li>
 *   <li>NETWORK / IMAGE —— 数据源分别是 godeye-okhttp（要求特定 OkHttp 版本）与 Glide/Picasso，
 *       本工程两者都没用</li>
 * </ul>
 */
public final class GodEyeDashboard {

    private static final String TAG = "GodEyeDashboard";

    private static volatile boolean sInstalled;

    private GodEyeDashboard() {
    }

    public static void init(@NonNull Application app) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            // noneConfigBuilder：默认什么都不装，模块逐个显式打开，避免意外引入插桩/依赖冲突模块
            GodEyeConfig config = GodEyeConfig.noneConfigBuilder()
                    .withCpuConfig(new CpuConfig())
                    .withBatteryConfig(new BatteryConfig())        // 功耗看板
                    .withFpsConfig(new FpsConfig())
                    .withHeapConfig(new HeapConfig())
                    .withPssConfig(new PssConfig())
                    .withRamConfig(new RamConfig())
                    .withThreadConfig(new ThreadConfig())          // 线程数量/状态，车机卡顿排查常用
                    .withTrafficConfig(new TrafficConfig())
                    .withCrashConfig(new CrashConfig())
                    .withSmConfig(new SmConfig())                  // 主线程卡顿阈值检测(默认 500ms) + 通知/前台事件
                    .withStartupConfig(new StartupConfig())        // 启动耗时
                    .withPageloadConfig(new PageloadConfig())      // 页面加载耗时
                    .withViewCanaryConfig(new ViewCanaryConfig())  // 冗余视图/层级检测
                    .withAppSizeConfig(new AppSizeConfig())
                    .build();

            GodEye.instance().init(app);
            GodEye.instance().install(config);
            GodEyeHelper.startMonitor();

            Log.i(TAG, "AndroidGodEye installed, modules="
                    + GodEye.instance().getInstalledModuleNames());
            Log.i(TAG, "dashboard: adb forward tcp:5390 tcp:5390 -> http://localhost:5390/index.html");
        } catch (Throwable t) {
            // 看板挂了不影响业务，也不该影响 Matrix 的采集
            Log.w(TAG, "AndroidGodEye install failed: " + t);
        }
    }
}
