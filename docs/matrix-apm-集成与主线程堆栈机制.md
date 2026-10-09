# 腾讯 Matrix 集成说明 + 主线程耗时堆栈捕获机制

> 本工程状态：`main`（Gradle 9.3.1 / AGP 9.1.1）+ **免插桩子集**。
> 证据标注：**[测]** 本机从 aar/jar 反解验证；**[官]** 官方制品仓库核对；**[推]** 推断未验证。
> 版本：`com.tencent.matrix:*:2.1.0`（Maven Central 该 group 的最新版本 **[官]**，阿里云 `repository/public` 可达，0.25s **[测]**）

## 1. 为什么 main 上只能用免插桩子集

不是配置问题，是 AGP 版本问题 **[测]**：

| 检查项 | 结果 |
|---|---|
| `matrix-gradle-plugin:2.1.0` 的 pom 依赖 | `com.android.tools.build:gradle:**4.0.0**`、ASM **7.0**、kotlin-stdlib 1.4.32 |
| 引用已删除的 Transform API（`com/android/build/api/transform`）的类 | **11 个**，含 `MatrixTraceLegacyTransform`、`MatrixTraceInjection` |
| 引用 AGP 7.3+/8.x Instrumentation API（`AsmClassVisitorFactory`、`build/api/artifact`）的类 | **0 个** |
| 插件 hook 的 DSL 类型 | `com/android/build/gradle/AppExtension`（老式扩展） |

Transform API 在 AGP 8.0 被移除，AGP 9 里连兼容层都没有残留 ⇒ 插件在 `main` 上 apply 必然失败。ASM 7.0 最多读到 Java 13 时代的 class，也撑不住现代工具链。

因此：**想要完整的 TraceCanary（方法级耗时）只能待在 AGP ≤ 7.x 的栈上**，也就是你的 `dokit-plugin-compat` 分支那一类环境（详见 §7）。

## 2. 已经装进 main 的东西

依赖（`gradle/libs.versions.toml` + `app/build.gradle`）：

```
matrix-android-lib        核心（Matrix / Plugin / PluginListener / Issue / lifecycle）
matrix-android-commons    native 公共库
matrix-trace-canary       libtrace-canary.so：ANR 堆栈、Looper 监控、FPS、启动画像
matrix-io-canary          IOCanary（4 个 abi 的 so）
matrix-memory-canary      内存 PSS 监控 + trim 时机
matrix-battery-canary     功耗（wakelock / jiffies / 采样）
matrix-resource-canary-{android,common,analyzer}   Activity/Fragment 泄漏看护与 hprof 分析
androidx.tracing 1.3.0    Perfetto trace marker
androidx.metrics:metrics-performance 1.0.0   JankStats 掉帧统计
leakcanary-android 2.14   debugImplementation（仅 debug 变体）
```

代码接线：

- `app/src/main/java/com/example/myapplication/perf/MatrixAPM.java` —— 组装 5 个 Plugin，`PluginListener` 把 `Issue` 打到 logcat；ANR trace 落在 `filesDir/matrix/anr/`。
- `app/src/main/java/com/example/myapplication/perf/PerfMarkers.java` —— `Trace.beginSection` 封装（try-with-resources 的 `Scope`）、进程启动→首帧耗时打点、JankStats 业务上下文标签。
- `MyApplication.onCreate()` —— 原有 `new DoKit.Builder(this).build();` 保留未动，后面追加 `PerfMarkers.markAppCreateStart()` 与 `MatrixAPM.init(this, BuildConfig.DEBUG)`。

`TraceConfig.Builder` 的关键开关 **[测]**（javap 自 aar）：

```java
.enableAppMethodBeat(false)     // 关掉：方法出入口要靠插件注入，注入不了就是空转
.enableEvilMethodTrace(false)// 关掉：同上
.enableAnrTrace(true)
.enableSignalAnrTrace(true)  // ★ 信号式主线程堆栈：纯运行时，不需要插桩
.enableFPS(true)
.enableStartup(true)         // 启动耗时画像：也不需要插桩
.enableIdleHandlerTrace(debug)
.looperPrinterStackStyle(int)
.anrTracePath(...) / .printTracePath(...)
```

`ResourceConfig.DumpMode` 选了 **`NO_DUMP`** **[测]**（枚举含 `NO_DUMP / AUTO_DUMP / MANUAL_DUMP / SILENCE_ANALYSE / FORK_DUMP / FORK_ANALYSE / LAZY_FORK_ANALYZE`）：把 heap dump 让给 LeakCanary，否则两套工具会在同一进程里反复抓堆，本身就是卡顿与 OOM 源。

## 3. 主线程耗时堆栈是怎么抓到的（重点）

Matrix 在这件事上是**三层机制拼起来的**，理解层次比记住类名有用。以下全部本机反解验证 **[测]**。

### 3.1 LooperMonitor —— 定位"哪条消息慢"

`com.tencent.matrix.trace.core.LooperMonitor`：

```
public class LooperMonitor implements android.os.MessageQueue$IdleHandler
  static void register(LooperDispatchListener) / unregister(...)
  public static LooperMonitor getMainMonitor();  public static LooperMonitor of(Looper)
```

实现方式：反射取 `android.os.Looper` 的 `mLogging` 字段并调用 `setPrinter`（字节码里直接可见 `android/os/Looper`、`mLogging`、`setPrinter` 三个符号 **[测]**），把系统原有的 Printer 包一层。每次 `messageStart`/`messageEnd` 就是一对时间戳，配 `IdleHandler` 识别空闲段。

- 纯运行时，**不需要插件**，所以 main 上可用。
- 产出是"消息粒度"的耗时：dispatch 了哪条 Message、耗时多少、target handler 是谁。它能告诉你"主线程被一条 800ms 的消息占住了"，但**不能**告诉你那 800ms 里执行的是哪个方法。

### 3.2 SignalAnrTracer —— 定位"慢的那一刻栈在哪"

`com.tencent.matrix.trace.tracer.SignalAnrTracer`（注意包名是 `tracer` 不是 `core`）：

```
public SignalAnrTracer(Application, String anrTracePath, String printTracePath)
public void setSignalAnrDetectedListener(SignalAnrDetectedListener)
public static void printTrace();  public static void setAnrReportTimeout(long)
public static String readCgroup()
内部类：SimpleDeadLockDetector（含 ThreadNode/Pair —— 锁等待图）、SignalAnrDetectedListener
```

native 侧 `libtrace-canary.so` 里的符号与字符串 **[测]**：

```
MatrixTracer::AnrDumper
anrDumpCallback / anrDumpTraceCallback / hookAnrTraceWrite / unHookAnrTraceWrite
nativeBacktraceDumpCallback
onTouchEventLagDumpTrace            ← 触摸事件卡顿时也抓一次栈
sigaction                           ← 注册信号处理器
wechat_backtrace::unwind_adapter    ← 微信自研栈回溯库（matrix-backtrace 制品）
/data/anr/traces.txt
```

机制串起来：**用 `sigaction` 接管信号 → 卡死/ANR 时用 `wechat_backtrace` 直接回溯当前线程的 native+java 栈 → 同时 hook 系统写 ANR trace 的路径，把现场复制到自己的 `anrTracePath`**。`SimpleDeadLockDetector` 再做一层线程等待关系图，识别"互相等锁"这种比单帧更 nasty 的场景。`readCgroup` 用来判断进程是否已被系统冻结（后台 ANR 误报的常见根因）。

关键点：**这条链路完全不依赖字节码注入**，所以在 AGP 9 的 main 分支照样能拿到"主线程耗时堆栈"。你要研究的车机卡顿/ANR 现场还原，主力就是它。

### 3.3 AppMethodTrace / AppMethodBeat —— 定位"慢之前走过哪些方法"

`com.tencent.matrix.trace.core.AppMethodBeat`（含 `IndexRecord`、`MethodEnterListener`）+ `LooperAnrTracer`：给每个方法进入/退出打一个**索引记录**（不是耗时，是极轻量的位点序列），卡顿发生时回放这段时间的调用序列，从而把 3.1 的"某条消息慢"翻译成"这条消息里哪个方法被调了、调到哪一层"。

代价：索引必须由插件在字节码里插桩产生。**main 上无数据**，所以 §2 里我把 `enableAppMethodBeat(false)`、`enableEvilMethodTrace(false)` 显式关了 —— 开着只会白跑。

### 3.4 三层的关系（也是排查顺序）

```
3.1 LooperMonitor   →  哪条消息、耗时多久        （main 可用）
3.2 SignalAnrTracer →  那一刻的完整线程栈 + 锁等待图（main 可用 ★）
3.3 AppMethodTrace  →  卡顿前的方法调用轨迹        （需 AGP ≤7.x 插件）
```

实战上先 3.1 定阈值与现场时间窗，再用 3.2 的栈定位代码行；只有当栈落在框架层、看不出业务入口时，才需要 3.3 的轨迹补充。也就是说：**多数车机 ANR 归因用 3.1 + 3.2 就够，缺 3.3 主要损失的是"热点方法定位"的精度。**

## 4. 车机场景的适配建议

- **阈值**：车机多为 60fps 甚至更低刷屏，主线程消息阈值建议前台 500ms、后台 3s；ANR 现场文件写在 `filesDir` 而不是外置存储（多数车机 ROM 限制外部读写）。
- **`/data/anr/traces.txt` 在新版本与厂商 ROM 上常读不到**（SELinux 收紧），Matrix 用"自己 hook 写 + 自己回溯"绕开这点，这也是 3.2 存在的意义。别指望 adb 拉系统 traces 文件。
- **功耗维度**：`BatteryMonitorPlugin` 的 wakelock 超时与 jiffies 采样对车机更有意义（整车电源策略敏感），但 `enableStatPidProc` 这类需要读 `/proc` 的能力在部分 ROM 上会被限，出现空数据先怀疑权限而不是集成错误。
- **native hook 兼容性**：3.2 依赖符号 hook，厂商 ROM 的 linker 改名/加固会致使其降级。上线前至少在目标车型上验证一次"人为卡死能否拿到栈"。

## 5. Perfetto 侧的配合（排查启动慢/掉帧）

Matrix 给"结论"（这条消息慢、这个栈卡），Perfetto 给"时间轴"（IO、锁、布局解析分别占了多少）。两者互补：

```bash
# 抓 10s，含应用 trace marker（PerfMarkers 里的 beginSection 会出现在时间轴上）
adb shell perfetto -o /data/misc/perfetto-traces/launch.pftrace -t 10s \
  ftrace sched power mem_linux gfx input view wm sched_freq \
  -c '<buffers><buffer_size>32768</buffer_size></buffers> atrace apps'
adb pull /data/misc/perfetto-traces/launch.pftrace
```

代码侧已备好：`PerfMarkers.section("inflate-home")` 包任意可疑段；`markAppCreateStart()` / `markFirstFrame()` 输出进程启动→首帧两段耗时；JankStats 掉帧时把业务标签一起带上（`labelJankContext`）。

**AndroidGodEye 没装**：它在 Maven Central 无制品，jitpack 对应坐标返回 **401**，阿里云 public/jcenter 均 404 **[测]**，且上游自 2020 年后停更。实时看板这块改用官方 `androidx.metrics:metrics-performance`（JankStats）+ Perfetto，能力覆盖它的 FPS/CPU/内存看板中与你目标相关的部分；CPU/电量趋势若确实要"屏幕上的浮窗"，需要自绘或换 DoKit 自带的性能面板（工程里已集成 DoKit）。

## 6. 共存注意

- **LeakCanary vs Matrix ResourceCanary**：都会抓 hprof。现方案是 ResourceCanary `NO_DUMP` 只跑泄漏看护，dump 与引用链交给 LeakCanary（debug 变体）。要切到 Matrix 的 hprof 分析，就把 DumpMode 改成 `FORK_ANALYSE` 同时用 `leakDumpExclusion`/关掉 LeakCanary（`Config.dumpHeapTaskDaemon` 或移除依赖）。
- **DoKit**：DoKit 的插件式插桩同样属于 AGP 老工具链（这就是你那条 compat 分支存在的原因）。当前只在 debug 生效，与 Matrix 的运行时 hook 不冲突，但两者同时开满会造成明显卡顿 —— 压测时请只留一个。
- **release 包**：目前 `MatrixAPM.init` 在两个 buildType 都会执行（`isDebug` 参数不同）。正式发布若只想保留 ANR/功耗上报，建议把 TracePlugin 的 path 指到应用私有目录并加采样率控制。

## 7. 想在 AGP 7.x 分支拿完整能力

插件 id 与扩展 **[测]**（`META-INF/gradle-plugins/com.tencent.matrix-plugin.properties` → `implementation-class=com.tencent.matrix.plugin.MatrixPlugin`；扩展注册名 `matrix`）：

```groovy
buildscript {
    dependencies {
        classpath 'com.tencent.matrix:matrix-gradle-plugin:2.1.0'
    }
}
apply plugin: 'com.tencent.matrix-plugin'

matrix {
    trace {
        enable = true                       // MatrixTraceExtension.enable
        transformInjectionForced = false
        baseMethodMapFile = "${projectDir}/matrixMethodMap.txt"
        blackListFile = "${projectDir}/matrixBlackList.cfg"
        customDexTransformName = 'MatrixMethodTraceTransform'
        skipCheckClass = false
    }
}
```

`MatrixTraceExtension` 的可用属性就是上面这几个 **[测]**。开了插件后，把 §2 里的 `enableAppMethodBeat/enableEvilMethodTrace` 改回 `true`，3.3 那层数据才会出现。注意 `dokit-plugin-compat` 分支已有 DoKit 的 transform，两个 transform 叠加时建议先只开 Matrix 验证构建，再逐个加回。

## 8. 验证状态

| 项 | 状态 |
|---|---|
| 制品可达性与版本 | ✅ 实测（阿里云 + Central，2.1.0 全模块；`matrix-jectl` 无 2.1.0 已跳过） |
| API 签名（Builder/Plugin/Config/DumpMode） | ✅ 从 aar 反解 `javap` 核对，未凭记忆写 |
| 机制描述（Printer 钩子 / sigaction + wechat_backtrace / AppMethodBeat） | ✅ so 符号与 class 常量字符串实测 |
| 编译（`:app:assembleDevDebug`） | ✅ 实测通过，见 §9 补记（含两处真实报错与修法） |
| 运行时（ANR 现场真的能抓到栈） | ❌ **未验证**：本机 `adb devices` 无设备/模拟器，需要真机或车机环境跑一次人为卡死 |
| Perfetto 命令 | [推] 参数按通用配置给出，未在本机设备实跑 |

## 9. 补记：构建实测（2026-10-07）

`:app:assembleDevDebug --offline` → **BUILD SUCCESSFUL in 16s**，`REAL_EXIT=0`。产物 `app/build/outputs/apk/dev/debug/app-dev-debug.apk`（134,910,018 B / 8 个 dex）。

途中真实撞到并修掉两个坑（都不是一开始就想到的）：

1. **编译期：JankStats API 用错**。我先写了 `stats.stopTracking()`，javac 报 `找不到符号`。`javap androidx.metrics.performance.JankStats` 实测只有 `isTrackingEnabled()` / `setTrackingEnabled(boolean)`（Kotlin 属性到 Java 侧就是 setter），没有 `stopTracking`。
2. **打包期：`libc++_shared.so` 撞车**。`:app:mergeDevDebugNativeLibs` 报 `2 files found with path 'lib/arm64-v8a/libc++_shared.so'`，来源是 `matrix-backtrace-2.1.0` 与 `ffmpeg-kit-full-6.0-2` 各带一份同路径同 ABI 的 C++ 运行时。修法是 `android.packaging.jniLibs.pickFirsts`（Groovy DSL 下 `pickFirsts` 只能 `.add(...)`，`+=` 那种写法在这里不成立）。

```groovy
packaging {
    jniLibs {
        pickFirsts.add('**/libc++_shared.so')
    }
}
```

**pickFirst 之后 APK 里留下的是 Matrix 那份（旧）**，这是实测不是猜：

| ABI | matrix-backtrace 那份 | ffmpeg-kit 那份 | APK 实际打包 |
|---|---|---|---|
| arm64-v8a | 911,696 B / clang 9.0.8（NDK r21b） | 991,912 B / clang 11.0.5 | **旧（=Matrix）** |
| armeabi-v7a | 554,808 B | 600,852 B | **旧（=Matrix）** |
| x86 / x86_64 | 不带 | 983,744 / 1,039,104 B | ffmpeg 那份，无冲突 |

旧运行时会把 ffmpeg 弄崩吗？做了符号级核验（不是"应该没事"）：把 APK 内 4 个 ABI 的 so 全解出来，用 `readelf --dyn-syms` 取每个库未定义的 `_Z*` 符号，再对全 APK so 的导出符号集求交：

| 库（arm64-v8a） | 未定义 C++ 符号数 | 在 APK 内能否全部解析 |
|---|---|---|
| libffmpegkit.so | 0 | 不需要 |
| libavcodec.so | 5 | 是 |
| libavfilter.so | 39 | 是 |
| libavformat / libavutil / libswscale / libswresample | 0 | 不需要 |
| libwechatbacktrace.so | 57 | 是 |
| libtrace-canary.so | 2 | 是 |
| libmatrix_mem_util.so | 63 | 是 |

armeabi-v7a 同样结论。原因很朴素：ffmpeg-kit 的 JNI 桥是纯 C，只是链接参数里带了 `-lc++_shared`；真正的 C++ 使用方是 Matrix，而它用的正是自己那份。所以这里保留旧的一份是安全的，不需要再往 `src/main/jniLibs` 里塞二进回来强行翻盘。

```bash
# 复现核验（readelf 来自 D:/develops/tools/mingw64/bin，MinGW binutils 2.46 能读 ELF）
unzip -o app/build/outputs/apk/dev/debug/app-dev-debug.apk "lib/arm64-v8a/*" -d /tmp/apkso
for l in /tmp/apkso/lib/arm64-v8a/*.so; do
  readelf --dyn-syms --wide "$l" | awk '/(FUNC|OBJECT|WEAK)/ && $7!="UND"{print $8}' | sed 's/@.*//'
done | sort -u > /tmp/exports_all.txt
readelf --dyn-syms --wide /tmp/apkso/lib/arm64-v8a/libffmpegkit.so \
  | awk '$7=="UND"{print $8}' | sed 's/@.*//' | grep '^_Z' | sort -u \
  | comm -23 - /tmp/exports_all.txt     # 输出为空 = 全部可解析
```

产物层面已确认（实测，非推断）：

- **so 进包**：`libtrace-canary.so`、`libio-canary.so`、`libwechatbacktrace.so`、`libmatrix_hprof_analyzer.so`、`libmatrix_mem_util.so`、`libmmkv.so`，arm64-v8a / armeabi-v7a / x86 / x86_64 四个 ABI 齐全
- **APK manifest**（`aapt2 dump xmltree --file AndroidManifest.xml`）：权限 `com.example.myapplication.matrix.permission.PROCESS_SUPERVISOR`、`…manual.dump`、`…backtrace.warmed_up`；组件 `com.tencent.matrix.backtrace.WarmUpService`、`com.tencent.matrix.resource.CanaryWorkerService`、`CanaryResultService`；LeakCanary 的 `MainProcessAppWatcherInstaller`、`LeakLauncherActivity` 等也在；DoKit 的组件未受影响
- **dex**：`com/tencent/matrix/trace/tracer/SignalAnrTracer`、`trace/core/LooperMonitor`、`trace/core/AppMethodBeat`、五个 Plugin 类，以及本工程新增的 `com/example/myapplication/perf/MatrixAPM`、`PerfMarkers` 全部打入
- **调用点**：`MyApplication.onCreate()` 里 DoKit 那行原样保留，其后 `PerfMarkers.markAppCreateStart()` 与 `MatrixAPM.init(this, BuildConfig.DEBUG)`

一条已知的无害告警：`haha-2.0.3.jar`（LeakCanary 的堆解析器）在 `mergeExtDexDevDebug` 打了一串 `D8: Invalid signature … Parser error: Expected ;`，是它自己的泛型签名不合规，D8 丢弃签名后继续，产物不受影响，不处理。

至此 §8 的"编译"一行成立；**运行时那一行仍是 ❌**——符号与打包都能静态证明，"ANR 现场抓到主线程堆栈"必须上真机/车机跑一次人为卡死才能确认。

## 10. 更正与补记：AndroidGodEye 其实装得上（2026-10-07 深夜）

§5 里写的"AndroidGodEye 不可获取（jitpack 401、不在 Central）"**是错的**，错因很低级：我按作者真名猜了坐标 `com.github.maoruoyu:*`，而 GitHub 用户名是 **Kyson**。实测：

| 探测 | 结果 |
|---|---|
| `https://jitpack.io/com/github/maoruoyu/AndroidGodEyeLibrary/maven-metadata.xml` | 401 `Repo not found or no token provided`（我当时据此下了结论） |
| 对照实验 `https://jitpack.io/com/github/square/okhttp/maven-metadata.xml` | 200 —— 说明 jitpack 本身通，401 只代表仓库名不存在 |
| `gh api search/repositories?q=AndroidGodEye` | `Kyson/AndroidGodEye`，2639 stars，最后 push 2023-01-26 |
| `gh api repos/Kyson/AndroidGodEye/tags` | 最新 `3.4.3` |
| `com.github.Kyson:AndroidGodEye:3.4.3`（伞形 POM） | 200，POM 里指向 5 个子模块 aar |
| 子模块 aar 直取 | `godeye-core` 323,973B / `godeye-monitor` 4,729,779B / `godeye-leakcanary` / `godeye-okhttp` / `godeye-xcrash` 全 200 |
| 阿里云 public / Central / 华为云 同路径 | 404 —— **只有 jitpack 有**，所以 `settings.gradle` 必须加 jitpack 仓库 |

教训：搜不到制品时先验证"仓库整体是否可达"（拿一个已知存在的坐标做对照），再判断是网络问题还是坐标问题。

### 装法（debug 变体）

- `settings.gradle`：`dependencyResolutionManagement.repositories` 加 `maven { url 'https://jitpack.io' }`（本工程是 `FAIL_ON_PROJECT_REPOS`，只能在 settings 里加）
- `libs.versions.toml`：`godeye = "3.4.3"`，`godeye-core` / `godeye-monitor`，group 都是 `com.github.Kyson.AndroidGodEye`
- `app/build.gradle`：`debugImplementation(libs.godeye.core) { exclude group: 'com.android.support' }`，monitor 同理
- 代码：`app/src/debug/java/.../perf/GodEyeDashboard.java`（真实现）+ `app/src/release/java/.../perf/GodEyeDashboard.java`（空实现，保证 release 也能编译，因为依赖是 `debugImplementation`）；`MyApplication.onCreate()` 末尾 `GodEyeDashboard.init(this)`

**必须整组排除 `com.android.support`**：godeye 是 2020 年的库，传递依赖带 `support-compat:28.0.0` / `versionedparcelable:28.0.0`，与 `androidx.core:core:1.12.0`、`androidx.versionedparcelable:1.1.1` 同名类撞车，`:app:checkDevDebugDuplicateClasses` 直接失败（我第一次构建就死在这）。排除后仍有的 3 个 `android.support.v4.app.Fragment/FragmentManager` 引用查过来源，都在不会执行的路径上：`cn.hikyson.methodcanary.lib.Util`（METHOD 模块没开，见下）与 `leakcanary.internal.AndroidSupportFragmentDestroyWatcher`（LeakCanary 用 `Class.forName` 拿，dexdump 里能看到那句 const-string，拿不到就走 androidx 分支）。

### 模块取舍（逐个显式打开，`noneConfigBuilder` 起步）

API 也是 `javap` 出来的，不是抄 README：入口是 `GodEye.instance().init(app)` + `install(GodEyeConfig)`，看板服务是 `GodEyeHelper.startMonitor()`；`GodEyeMonitor.work(Context, int)` 是包内可见，外面调不到。

- 开了：CPU、BATTERY（功耗看板）、FPS、HEAP、PSS、RAM、THREAD、TRAFFIC、CRASH、SM、STARTUP、PAGELOAD、VIEW、APP SIZE
- 关了并写明原因：LEAK（要 `godeye-leakcanary`，基于旧 LeakCanary API，与本工程 2.14 冲突）、METHOD（要字节码插桩，AGP 9 上同 §1 的 Matrix 插件问题）、NETWORK/IMAGE（数据源分别是特定 OkHttp 版本与 Glide/Picasso，本工程没有）

### 实测结果

`./gradlew.bat :app:assembleDevDebug` → BUILD SUCCESSFUL in 51s，`:app:testDevDebugUnitTest` → 7/0（XML 时间戳 23:42）。APK 141,778,138 B：

- `assets/android-godeye-dashboard/`（内置前端：`index.html` + `main.f223975e.js` 2.9MB + css）—— 这就是"实时看板"的页面，不用另装 PC 客户端
- dex 里 206 个 `cn/hikyson/**` 类，含 `GodEye`、`GodEyeConfig`、`GodEyeHelper`、`monitor.GodEyeMonitor`、`monitor.server.GodEyeMonitorServer`
- manifest 合并进 `cn.hikyson.godeye.core.GodEyeInitContentProvider`（authority `com.example.myapplication.cn.hikyson.godeye.core.init`，Provider 自动初始化）与 `LocalNotificationListenerService`

看板用法：`adb forward tcp:8087 tcp:8087` → 浏览器 `http://localhost:8087`；车机若与 PC 同网段则直接 `http://<车机IP>:8087`。

### 与 Matrix 的分工（这才是"数据感"的完整答案）

| 需求 | 用什么 | 为什么 |
|---|---|---|
| 建立实时数据感（FPS/CPU/内存/电量曲线） | AndroidGodEye 看板 | PC 浏览器实时推送，适合盯盘 |
| 抓一次卡顿/ANR 的现场堆栈 | Matrix `SignalAnrTracer` + `LooperMonitor` | 看板只给数字，不给栈 |
| 启动慢的定位 | 两边配合 | GodEye STARTUP 给曲线，Matrix startup 给逐段耗时落盘 |
| 掉帧归因到业务代码 | JankStats + `PerfMarkers.section()` + Perfetto | `states` 里的业务标记能直接落到 trace |
| 泄漏 | LeakCanary 2.14（debug） | Matrix ResourceCanary 设 `NO_DUMP` 让位，见 §6 |

§8 的运行时那一行状态不变：本次新增内容同样只做到**构建/打包/符号层验证**，看板的实时刷新必须接设备后确认。haha-2.0.3 的 D8 签名告警照旧忽略（见 §9）。

## 11. 运行时验证：手机接不上，改用官方 AVD 跑通了（2026-10-09 晚）

§10 结尾那句"看板实时刷新必须接设备后确认"现在部分兑现了——不是真机，是本机新建的官方模拟器。**运行时一跑就炸出两个构建期看不见的真 bug**，这正是运行时验证的价值。

### 11.1 为什么走模拟器

真机始终没连上：USB 层每次都在"读设备描述符"这步失败，主机侧留的条目是占位 ID `USB\VID_0000&PID_0002`，Problem = `CM_PROB_FAILED_POST_START`（"未知 USB 设备(设备描述符请求失败)"）。手机重启后 `LastArrivalDate` 从 19:37:07 跳到 19:52:33，说明它重新上线过一次、主机重新枚举过一次，失败一模一样 —— 排除了手机软件状态，剩下的是线 / 尾插 / 口的信号问题。诊断脚本在 `tools/usb-adb-check.ps1`（五步：找 adb → `adb devices -l` → `Get-PnpDevice` 带 Problem 码 → MTP/便携设备是否出现 → Kernel-PnP 410/411/430 事件）。

本机的模拟器的条件先核过：`emulator -accel-check` 输出 `WHPX(10.0.26200) is installed and usable`（Hyper-V 平台在跑，官方模拟器正好用它），`dl.google.com` 直连可达（4096×100 字节采样 1.3 MB/s），C: 剩 27 GB。

### 11.2 AVD 落地配方（含两个卡点）

| 步骤 | 实测做法 | 卡点与结论 |
|---|---|---|
| cmdline-tools | `$Sdk/cmdline-tools/latest` 里只有 `.installer` 空壳，**没有 `bin/`**，`sdkmanager`/`avdmanager` 全不可用 | 直接下 `commandlinetools-win-14742923_latest.zip`（150,532,528 B，2026-01-20），解到 `$Sdk/cmdline-tools/qoder/` —— 必须是 `<sdk>/cmdline-tools/<任意名>/bin` 结构，工具才认得出 SDK 根 |
| 装镜像 | `sdkmanager --sdk_root=$Sdk 'system-images;android-34;google_apis;x86_64'` | `x86_64-34_r14.zip`，装完占 4.2 GB；选 `google_apis` 而不是 `playstore`，因为要 `adb root`/调试自由度 |
| 建 AVD | `avdmanager create avd -n mx_avd -k 'system-images;android-34;google_apis;x86_64' -d pixel_6` | 第一次报 `Package path is not valid`：工具是从 D: 解压出来的，SDK 根推错了；搬进 `$Sdk/cmdline-tools/qoder` 后成功。AVD 放 `D:\develops\android\avd`（`ANDROID_AVD_HOME`），给 C: 省 6 GB 数据分区 |
| 启动 | `emulator -avd mx_avd -gpu swiftshader_indirect -no-boot-anim -no-audio -no-snapshot-save -memory 2048 -cores 4` | `sys.boot_completed=1` 用时约 40 s；`ro.product.cpu.abi = x86_64`，SDK 34 |
| 装包 | `adb install -r -g app/build/outputs/apk/dev/debug/app-dev-debug.apk`（142 MB） | Streamed Install，4 s。**不要用 `-G 2G`**，那台 emulator 会在 `PackageManagerShellCommand` 抛栈 |

### 11.3 Matrix 侧：`dynamicConfig(null)` 会把整个 APM 打断

第一次运行的日志（原样）：

```
W MatrixAPM: Matrix startup failed, APM degraded
E MatrixAPM: java.lang.NullPointerException: Attempt to invoke interface method
    'boolean com.tencent.mrs.plugin.IDynamicConfig.get(String, boolean)' on a null object reference
    at com.tencent.matrix.iocanary.config.IOConfig.isDetectFileIOInMainThread(IOConfig.java:61)
    at com.tencent.matrix.iocanary.core.IOCanaryCore.initDetectorsAndHookers(IOCanaryCore.java:80)
    at com.tencent.matrix.iocanary.IOCanaryPlugin.start(IOCanaryPlugin.java:62)
    at com.tencent.matrix.Matrix.startAllPlugins(Matrix.java:84)
```

`Matrix.startAllPlugins()` 是**一个循环里按顺序 start**，第 4 个插件抛异常，排在它后面的 MemoryCanary / BatteryMonitor 就再也不 start 了。写代码时以为 `dynamicConfig(null)` = "不用云端开关"，实际是"给 null 然后插件在 start 里 NPE"。修法：自己实现 `IDynamicConfig`（5 个重载 `get(String, X)`，接口在 `matrix-android-lib` 里，别处没有），每个都直接返回 `defValue`。

`com.tencent.mrs.plugin.IDynamicConfig` 的全部方法（`javap` 实测，只有这 5 个）：

```java
String get(String, String);  int get(String, int);  long get(String, long);
boolean get(String, boolean); float get(String, float);
```

### 11.4 Matrix 的 native 覆盖是按 ABI 分布的，x86_64 上少两个库

把三个 AAR 的 `jni/` 逐个 `unzip -l` 得到：

| so | 来自 | arm64-v8a | armeabi-v7a | x86 | x86_64 |
|---|---|---|---|---|---|
| `libtrace-canary.so` | matrix-trace-canary | ✅ | ✅ | ❌ | ❌ |
| `libwechatbacktrace.so` | matrix-backtrace / resource-canary | ✅ | ✅ | ❌ | ❌ |
| `libio-canary.so` | matrix-io-canary | ✅ | ✅ | ✅ | ✅ |
| `libmatrix_hprof_analyzer.so` / `libmatrix_mem_util.so` | matrix-resource-canary-android | ✅ | ✅ | ✅ | ✅ |
| `libc++_shared.so`（§9 那次撞车的当事人） | matrix-backtrace 与 ffmpeg-kit | ✅ | ✅ | — | ✅ |

所以 x86_64 模拟器上卡顿/ANR/资源泄漏这三块**根本没有 native 支撑**，硬装就是在 `Plugin.init()` 里 `UnsatisfiedLinkError`。`MatrixAPM` 现在先探 `ApplicationInfo.nativeLibraryDir` 里有没有对应文件，再决定挂哪些插件：

```
W MatrixAPM: TracePlugin skipped: libtrace-canary.so absent in /data/app/~~…/lib/x86_64 (ANR/FPS/Startup 无 native 支撑)
W MatrixAPM: ResourcePlugin skipped: libwechatbacktrace.so / libmatrix_hprof_analyzer.so 不全
I MatrixAPM: plugin init   : BatteryMonitorPlugin / Matrix.MemoryCanaryPlugin / io
I MatrixAPM: plugin start  : BatteryMonitorPlugin / Matrix.MemoryCanaryPlugin / io
I MatrixAPM: Matrix installed, plugins=3
```

这条降级路径对 x86 车机同样有意义：**别假设 Matrix 的 so 一定在**。顺带说明 §9 那个 `pickFirsts` 的 libc++ 之争只在 arm64/armeabi 上成立，x86_64 上 matrix-backtrace 没有 so，打包进包的是 ffmpeg-kit 那份新的，不存在旧库覆盖问题。

### 11.5 AndroidGodEye 侧：SM 模块的前台服务在 targetSdk 34+ 必崩

```
E AndroidRuntime: java.lang.RuntimeException: Unable to start service
  cn.hikyson.godeye.core.internal.notification.LocalNotificationListenerService …
  android.app.MissingForegroundServiceTypeException: Starting FGS without a type
  callerApp=ProcessRecord{… com.example.myapplication …} targetSDK=36
```

看 godeye-core 自带的 `AndroidManifest.xml`：它 `<uses-sdk targetSdkVersion="29">`，`LocalNotificationListenerService` 声明里没有 `foregroundServiceType`，而 `SmConfig`（SystemMeasure，`debugNotification=true`）会 `startForegroundService` 拉起它。Android 14 起 targetSdk≥34 必须带类型，于是**装上就崩**。库的源码不在本工程，改不了，就在 `app/src/debug/AndroidManifest.xml`（debug 变体专属，release 里根本没有 GodEye）用 `tools:node="merge"` 给它补声明：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<service android:name="cn.hikyson.godeye.core.internal.notification.LocalNotificationListenerService"
    android:foregroundServiceType="dataSync" tools:node="merge" />
```

改完系统日志变成 `ActivityManager: Background started FGS: Allowed …`，进程存活。§10 里"看板可用"的结论成立，但**必须带这个补丁**，否则 debug 包一启动就闪退。

### 11.6 §10 的端口写错了：是 5390，而且根路径 404

实测日志：`AndroidGodEye monitor is running at port [5390]`。所以 `adb forward tcp:8087 tcp:8087` 转发过去必然连不上（那次 curl 报 `Empty reply from server` 就是这个原因）。正确用法：

```
adb forward tcp:5390 tcp:5390
浏览器打开 http://localhost:5390/index.html     # / 返回 404，必须带 /index.html
```

`/index.html`、`/favicon.ico`、`/static/js/main.f223975e.js`（2.9 MB，看板本体）、`/manifest.json` 都由设备侧直接吐出，无需 PC 端工具。

### 11.7 看板实时读数（2026-10-09 20:29，页面结构快照原样）

| 模块 | 实测值 |
|---|---|
| FPS | 59 / 60 |
| Battery | Not Charging（模拟器无电池事件） |
| Ram / Pss | 1973.7 M / 93.02 M |
| CPU | App 1.9 %，Device 0.0 % |
| Heap | Allocated 16.7 M，Max 192.0 M |
| Traffic | App ↓0.4 ↑22.4，Device ↓22.9 ↑23.0 KB/s |
| App Size | Code 135.25 MB，Cache 0.04 MB，Data 0.30 MB |
| Startup / PageLoad | `MainActivity` `ON_DRAW` Cost **1270 ms**（冷启首帧含 SoD 加载） |
| Thread | 表格实时列出 `main`、`binder:5690_*`、`matrix_li`、`default_matrix_thread`、`LeakCanary-Heap-Dump`、`AsyncServer` 等，3 页 |
| Block(卡顿) | SM 探测到并弹告警：`Jank happened.(发生长卡顿): 718ms` / `598ms` |
| View Canary | 告警 `Too many layouts nested or too much overdraw: com.example.myapplication.MainActivity` |
| MethodCanary | `Please install method canary first!`（预期，§7 说的 AGP 9 装不了插件） |
| Crash Info | 面板在，之前那两次 FGS 崩溃发生在补丁前，没有采集到 |

值得单独一句：**SM 的卡顿探测能报出 718ms 这种长卡顿时长**，虽然它给不出堆栈（给栈是 Matrix `SignalAnrTracer` 的活），但在 x86 设备上算是把"有没有卡"这一层补上了。

### 11.8 现在的验证状态（覆盖 §8 最后一行）

| 层面 | 状态 |
|---|---|
| 构建 / 打包 / 符号 / manifest / dex | ✅ §9、§10 |
| 运行时：APM 不崩、插件按 ABI 降级、看板实时刷新 | ✅ 本节，API 34 x86_64 AVD |
| 运行时：ANR 现场主线程堆栈（`SignalAnrTracer` + `libwechatbacktrace`） | ⛔ 仍未验证，**只能等 ARM 真机/车机**；x86_64 上这两个 so 不存在，造 ANR 也不会有产物 |
| 运行时：arm64 上 `pickFirsts` 选中的旧 libc++ 与 ffmpeg 共存 | ⛔ 同上，需 ARM 设备 |

复现命令（一次性）：

```
$Sdk/cmdline-tools/qoder/bin/sdkmanager.bat --sdk_root=$Sdk 'system-images;android-34;google_apis;x86_64'
ANDROID_AVD_HOME=D:\develops\android\avd avdmanager create avd -n mx_avd -k 'system-images;android-34;google_apis;x86_64' -d pixel_6
$Sdk/emulator/emulator.exe -avd mx_avd -gpu swiftshader_indirect -no-boot-anim -no-audio -no-snapshot-save -memory 2048 -cores 4
adb install -r -g app/build/outputs/apk/dev/debug/app-dev-debug.apk
adb shell am start -n com.example.myapplication/.MainActivity
adb logcat -d -s MatrixAPM:V GodEyeDashboard:V AndroidRuntime:E
adb forward tcp:5390 tcp:5390    # 浏览器 http://localhost:5390/index.html
```


