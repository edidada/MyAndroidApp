# Gradle / JDK / Kotlin / AGP / Foojay —— 五个维度对照手册

> 生成于 2026-10-07，针对本工程（`main` = Gradle 9.3.1 + AGP 9.1.1；`dokit-plugin-compat` = Gradle 7.6.4 + AGP 7.4.2 + KGP 1.8.22）。
> 标注说明：**[官]** = 官方文档核对；**[测]** = 本机实测得出；**[推]** = 推断，未在本地验证。

## 0. 先分清五个维度，它们互不等价

| 维度 | 它管什么 | 谁决定它 | 配置位置 |
|---|---|---|---|
| **Gradle 版本** | 构建引擎本身 + 它能跑在哪个 JVM 上 | `gradle-wrapper.properties` 的 `distributionUrl` | wrapper |
| **JDK 版本（构建用）** | 跑 Gradle daemon / 跑 javac 与 kotlinc 的那个 JDK | 依次由 `java.home`、`JAVA_HOME`、`org.gradle.java.home`、toolchain 决定 | gradle.properties / AS Settings / toolchain |
| **JVM target（产物字节码）** | `.class` 的 major version，即"生成给哪个 Java 版本运行" | `compileOptions.targetCompatibility`、`kotlin { jvmToolchain() }`/`jvmTarget`、`--release` | 各模块 build.gradle |
| **AGP 版本** | Android 构建插件；反过来约束 Gradle 下限、内置 KGP/D8/R8 版本 | `plugins` 别名或 `buildscript.classpath` | libs.versions.toml / 根 build.gradle |
| **Kotlin 版本** | 语言与编译器；决定能用的语法、能编到哪个 jvm-target、兼容哪些 Gradle/AGP | KGP 插件版本，或 **AGP 9 的内置 Kotlin** | libs.versions.toml 或 AGP 自带 |

三条最容易被搞混的不等价关系 **[官+测]**：

1. **JDK 版本 ≠ JVM target**。用 JDK 17 编译，产物照样可以是 Java 11 字节码（`--release 11`）。反过来不行：JDK 17 的 javac 无法产出 Java 21/24 字节码。
2. **AGP 版本 ≠ Gradle 版本**。AGP 只给 Gradle 的**下限**，不限上限（但有"未测试"告警）。
3. **Kotlin 版本不再必须自己声明**。AGP 9 起 Kotlin 由 AGP 内置提供，工程里看不到 `kotlin-android` 插件也能编 `.kt`（见 §5）。

本机实测证据（`javac 17.0.12`） **[测]**：

```
javac --release 8   -> class major 52
javac --release 11  -> class major 55
javac --release 17  -> class major 61
javac --release 21  -> 失败：JDK 17 的 javac 不支持 release 21（上限即自身版本）
```

## 1. AGP → Gradle 最低版本 **[官]**

来源：`developer.android.google.cn/build/releases/about-agp`（官方"插件版本 ↔ 所需最低 Gradle"表，逐行摘录）

| AGP | 最低 Gradle | AGP | 最低 Gradle |
|---|---|---|---|
| 9.4 | 9.6.0 | 8.8 | 8.10.2 |
| 9.3 | 9.5.0 | 8.7 | 8.9 |
| 9.2 | 9.4.1 | 8.6 | 8.7 |
| **9.1** | **9.3.1** | 8.5 | 8.7 |
| 9.0 | 9.1.0 | 8.4 | 8.6 |
| 8.13 | 8.13 | 8.3 | 8.4 |
| 8.12 | 8.13 | 8.2 | 8.2 |
| 8.11 | 8.13 | 8.1 | 8.0 |
| 8.10 | 8.11.1 | 8.0 | 8.0 |
| 8.9 | 8.11.1 | **7.4** | **7.5** |
| 7.3 | 7.4 | 7.2 | 7.3.3 |
| 7.1 | 7.2 | 7.0 | 7.0 |

对照本工程 **[测]**：`main` 的 AGP 9.1.1 要求 Gradle ≥ 9.3.1，wrapper 正好是 9.3.1 —— 踩在下限，没有余量；`dokit-plugin-compat` 的 AGP 7.4.2 要求 ≥ 7.5，实际 7.6.4 ✅。

## 2. AGP → JDK **[官+测]**

- AGP 8.x 需要 **JDK 17** **[官]**（原文："Android Gradle 插件版本 8.x 需要 JDK 17"）。
- AGP 9.0 发布说明：**最低 Gradle 9.1.0、最低 JDK 17**；AGP 9.4 页面写 "JDK 最低 17 / 默认 17" **[官]**。
- 有意思的实测 **[测]**：AGP 插件 jar 自身的字节码，7.4.2 和 9.1.1 **都是 major 55（Java 11）**。也就是说"AGP 要求 JDK 17"不是靠插件字节码门槛实现的，而是 Gradle daemon 与 AGP 内部校验实现的 —— 排错时别拿 `class major version` 去推断 AGP 的 JDK 要求。

## 3. Gradle → 可用 Java（daemon 运行 / toolchain 编译）**[官]**

来源：`docs.gradle.org/current/userguide/compatibility.html`（"支持 toolchain 编译"与"支持运行 Gradle"两列）

| Java | 编译下限 Gradle | 运行 daemon 下限 Gradle |
|---|---|---|
| 8 | —（仅运行） | 2.0+ |
| 11 | —（仅运行） | 5.0+ |
| 15 | 6.7+ | 6.7+ |
| 16 | 7.0+ | 7.0+ |
| 17 | 7.3+ | 7.3+ |
| 19 | 7.6+ | 7.6+ |
| 20 | 8.1+ | 8.3+ |
| 21 | 8.4+ | **8.5+** |
| 22 | 8.7+ | 8.8+ |
| 23 | 8.10+ | 8.10+ |
| 24 | 8.14+ | 8.14+ |
| 25 | 9.1.0+ | 9.1.0+ |

同页还写明：JVM 28 及以后尚未支持；现代 Gradle daemon 只接受 JVM 17–27。

注意"编译下限"和"运行下限"经常差一个小版本（Java 21：8.4 能编，8.5 才能跑）。这就是 toolchain 的价值 —— daemon 停在 JDK 17，用另一个 JDK 去编译。

## 4. KGP（自管 Kotlin 插件）→ Gradle **[官]**

来源：`kotlinlang.org/docs/gradle-configure-project.html`

| KGP | 支持 Gradle | KGP | 支持 Gradle |
|---|---|---|---|
| 2.4.20 | 7.6.3 – 9.7.0 | 2.1.0–2.1.10 | 7.6.3 – 8.10\* |
| 2.4.0–2.4.10 | 7.6.3 – 9.5.0 | 2.0.20–2.0.21 | 6.8.3 – 8.8\* |
| 2.3.20–2.3.21 | 7.6.3 – 9.3.0 | 2.0.0 | 6.8.3 – 8.5 |
| 2.3.10 / 2.3.0 | 7.6.3 – 9.0.0 | 1.9.20–1.9.25 | 6.8.3 – 8.1.1 |
| 2.2.20–2.2.21 | 7.6.3 – 8.14 | 1.9.0–1.9.10 | 6.8.3 – 7.6.0 |
| 2.2.0–2.2.10 | 7.6.3 – 8.14 | **1.8.20–1.8.22** | **6.8.3 – 7.6.0** |
| 2.1.20–2.1.21 | 7.6.3 – 8.12.1 | 1.8.0–1.8.11 | 6.8.3 – 7.3.3 |

官方口径：超出上界仍可用，但可能碰到废弃告警或特性不可用；下界是硬的。

对本工程的直接影响 **[测]**：`dokit-plugin-compat` 用 KGP 1.8.22 + Gradle 7.6.4。Gradle 7.6.4 **略高于 1.8.22 的文档上限 7.6.0**（补丁号差异，实践无碍，但别再往上涨 Gradle）。

## 5. AGP 9 的内置 Kotlin（本工程 `main` 正在用）**[测]**

`utils/build.gradle` 只 apply 了 `alias(libs.plugins.android.library)`，没有任何 Kotlin 插件，但 `utils/src/main/java/com/example/utils/MathUtils.kt` 照样编译并产出 class；而 `dao/build.gradle` 里显式写了 `android { enableKotlin = false }`（注释："This module only contains Java Room code."）。这不是巧合，是 AGP 9 的内置 Kotlin 支持。

实测到的三点：

1. **内置 KGP 版本 = 2.2.10**：从缓存里 `com.android.tools.build:gradle:9.1.1` 的 pom 直接读到 `<artifactId>kotlin-gradle-plugin</artifactId><version>2.2.10</version><scope>runtime</scope>`；本地 `org.jetbrains.kotlin/kotlin-gradle-plugin/` 也只有 1.8.22、2.1.10、2.2.10 三个版本，2.2.10 正是 AGP 拉下来的那个。
2. **开关**：AGP 9.1.1 的 class 里能 grep 到 `builtInKotlin`（`ComponentImpl`）与 `enableKotlin`（`ComponentDslInfo`）—— 即 Gradle 属性 `android.builtInKotlin` 与模块 DSL `android { enableKotlin = ... }` 两条路径 **[官：Flutter 文档亦记载 `android.builtInKotlin=true/false`]**。
3. **jvmTarget 自动跟随 compileOptions**：`utils` 里 `StringUtils.class`（Java，`targetCompatibility 11`）与 `MathUtils.class`（Kotlin）实测 **major 都是 55** —— 内置 Kotlin 没有独立的 jvmTarget 需要你配，它对齐 `compileOptions`。

AGP 9.0 发布说明另有一句关键约束 **[官]**：内置 Kotlin 会强制一个 KGP 版本基线（文档记载为 2.2.10），想偏离只能通过显式版本声明覆盖；同时老的 `kotlin-android` 插件不再被自动 apply，残留引用会导致构建失败。

## 6. Kotlin → 能编到哪个 JVM target **[测+官]**

本机用缓存的 `kotlin-compiler-embeddable-2.2.10.jar` 直接探测 `-jvm-target`（传非法值让编译器自己列清单） **[测]**：

```
Supported versions: 1.8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24
```

即 Kotlin 2.2 编译器最高能产出 Java 24 字节码、最低 1.8。Kotlin 2.3.0 起官方加入 Java 25 字节码生成能力 **[官]**；同时 Kotlin 2.3 彻底移除 `-language-version=1.8`（1.9 在非 JVM 平台也移除），2.0 以前的语言集不再支持 **[官]**。

Kotlin ↔ AGP/R8 的官方最低要求 **[官]**（`developer.android.google.cn/build/kotlin-support`）：

| Kotlin | 需要 AGP | 需要 R8 |
|---|---|---|
| 2.4 | 8.5.2+ | 9.1.29 |
| 2.3 | 8.2.2 – 8.13 | 8.13.19（<9.0.28 不支持 2.3） |
| 2.2 | 7.3.1 – 8.10 | 8.10.21 |
| 2.1 | 7.4.2 – 8.7.2 | 8.6.17 |
| 2.0 | 7.4.2 – 8.3 | 8.5.10 |
| 1.9 | 7.4.2 – 8.2 | 8.0.27 |
| 1.8 | 4.1.3 – 7.4 | 4.0.48 |

这张表的区间是给"你自己 apply KGP"用的；AGP 9 用内置 Kotlin 时按 §5 的内置版本走。D8/R8 随 AGP 一起发布，版本与 AGP 对齐。

## 7. 字节码 major 数字对照 **[测+标准]**

| Java | major | | Java | major |
|---|---|---|---|---|
| 8 | 52 | | 21 | 65 |
| 11 | 55 | | 24 | 68 |
| 17 | 61 | | 25 | 69 |

左边三档（52/55/61）本机用 `javac --release` 实测确认；65/68/69 为标准映射，本机 JDK 17 无法产出（这正是需要 toolchain 的场景）。

核对任意 class 的归属：**[测]**

```bash
od -An -j6 -N2 --endian=big -tu2 Some.class      # 直接读 major
```

## 8. JDK 从哪来：优先级与 Foojay **[官+测]**

Gradle 决定用哪个 JDK 的顺序（后者覆盖前者场景不同，实践上按此优先级排查）：

1. `org.gradle.java.home`（gradle.properties）—— 强制 daemon 用某 JDK，AS 里改 JDK 设置常写成这个。
2. daemon JVM：AS Bundled JBR 或 `JAVA_HOME`/PATH 上的 java。
3. **Java toolchain**（`java { toolchain { languageVersion = JavaLanguageVersion.of(N) } }`）—— 只影响编译/测试的 fork，不动 daemon。
4. **Foojay resolver**（`org.gradle.toolchains.foojay-resolver-convention`）—— 让 3 里本地缺的 JDK 自动从 Adoptium 等下载。

本机现状 **[测]**：

- `settings.gradle` 第 15 行：`id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'`；缓存里同时存在 0.8.0 和 1.0.0 的 plugin marker。
- 系统 `java` = **17.0.12**，`JAVA_HOME` = `D:\develops\jdks\Java\jdk-17`。
- `~/.gradle/jdks/` 里有已成功 provisioning 的 **Adoptium 21.0.7**，以及 **21.0.9_10 的 `.part` 半成品 + 遗留 `.lock`** —— 这个 `.part` 是下载中断的残留，和 Gradle 发行版本体那次的症状一模一样，遇到 toolchain 反复失败可以先清掉它。
- 官方口径：foojay 只 provisioning 正式版，**不含 early-access 版本**；Gradle 页文档未列插件本身的 Gradle 下限 **[官]**。

一个坑 **[推]**：AGP 9 内置 Kotlin 场景下，`kotlin { jvmToolchain(N) }` 与 `compileOptions.targetCompatibility` 是两个不同旋钮 —— 前者挑"用哪个 JDK 编译"，后者定"产物字节码是几"。要真正升到 Java 17 字节码，得同时改 `targetCompatibility`（并保证有对应 toolchain），单改一个就会出现"编译过了但 major 还是 55"。

## 9. 本工程两套栈速查 **[测]**

| | `main` | `dokit-plugin-compat` |
|---|---|---|
| Gradle | 9.3.1（= AGP 9.1 的官方下限，零余量） | 7.6.4（本次由本地 zip 灌入 wrapper 缓存） |
| AGP | 9.1.1（`libs.versions.toml` 别名 + `apply false`） | 7.4.2（根 `build.gradle` 的 `buildscript.classpath`） |
| Kotlin | **AGP 内置 KGP 2.2.10**，工程里没有 kotlin 插件声明 | KGP 1.8.22 显式 classpath（超出其 Gradle 文档上限 7.6.0 一个补丁） |
| 构建 JDK | 17.0.12（AGP 9.1 官方下限 17） | 17.0.12（AGP 7.x 的 JDK 下限官方 about-agp 页未列，通用记载为 11 **[推]**；17 必然满足，本机实测可跑） |
| compileSdk | app 36.1 / dao 36 / utils 34 | 34（另有 `android.suppressUnsupportedCompileSdk=34` 抑制告警） |
| targetCompatibility | 全模块 `JavaVersion.VERSION_11` → 实测 major 55 | 同为 11 |
| gradle.properties | 只剩 `jvmargs=-Xmx2048m` | 含 DOKIT_* 一组开关与 useAndroidX/jetifier |
| 写法差异 | catalog `alias(libs.plugins.…)` | `buildscript{}` + `apply plugin:` |

最后两行值得留意：**两套栈是"故意不统一"的**（dokit 3.5.0 只到 AGP 7.4 时代），跨分支抄配置会翻车 —— §10 里那条报错就是真实案例。

## 10. 报错 → 归因到哪个维度

| 报错关键词 | 真实维度 | 处置 |
|---|---|---|
| `Unsupported class file major version 6x` | Gradle 跑在了过旧 JDK 上（§3 daemon 列） | 换 daemon JDK 或升 Gradle |
| `Android Gradle plugin ... requires Gradle X` | AGP → Gradle 下限（§1） | 升 wrapper |
| `plugin is already on the classpath with an unknown version` | **不是版本问题**：`buildscript.classpath` 与 `plugins{别名带版本}` 混用，或构建期间切了分支导致两套写法交叉（本工程实测踩过，白跑 50 分钟） | 统一写法；构建期间别动 worktree |
| `Could not install Gradle distribution ... Connect timed out` | Gradle 发行版下载（§9 之外的网络维度） | 本地 zip 灌 wrapper dists 缓存 + 空 `.ok` |
| `SDK XML versions up to 3 ... version 4` | AGP 与 cmdline-tools 年代差，属告警 | 忽略即可（本机两套 AGP 都打这条，实测无害） |
| `Unknown JVM target version` | KGP 版本 → target 上限（§6） | 降 target 或升 Kotlin |
| `Cannot find a Java installation ... languageVersion=21` | toolchain + Foojay（§8） | 清 `~/.gradle/jdks` 的 `.part`/`.lock`，或显式配 toolchain JDK 路径 |

## 11. 可复制的自查命令 **[测]**

```bash
# 五维一次性打印
./gradlew.bat --version                                  # Gradle + 其使用的 JVM
java -version                                            # daemon 候选 JDK
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
grep -E '^agp|^kotlin' gradle/libs.versions.toml
grep -n "sourceCompatibility\|targetCompatibility\|compileSdk\|enableKotlin" */build.gradle

# AGP 内置的 KGP 版本（不用猜，读 pom）
find ~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/gradle/<ver> -name '*.pom' \
  | xargs grep -A1 kotlin-gradle-plugin

# 某模块实际产出的字节码 major（=JVM target 落地结果）
od -An -j6 -N2 --endian=big -tu2 utils/build/tmp/kotlin-classes/debug/com/example/utils/MathUtils.class

# 编译器自报支持的 jvm-target
java -cp <kotlin-compiler-embeddable.jar>:<kotlin-stdlib.jar> \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target BOGUS
```
