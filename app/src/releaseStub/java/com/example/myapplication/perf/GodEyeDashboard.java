package com.example.myapplication.perf;

import android.app.Application;

import androidx.annotation.NonNull;

/**
 * release 变体的空实现：AndroidGodEye 的 godeye-core / godeye-monitor 是 debugImplementation，
 * 正式包里根本没有这些类，所以真实现放在 {@code src/debug/java}，这里只保住调用点能编译。
 *
 * <p>目录名故意不叫 {@code src/release}：根 .gitignore 第 27 行有 {@code release/}，
 * 那个路径下的文件不会被 git 跟踪（构建没问题，但 clone 出来 release 编不过）。
 * 这里通过 app/build.gradle 的 sourceSets.release.java.srcDirs 挂进来。
 */
public final class GodEyeDashboard {

    private GodEyeDashboard() {
    }

    public static void init(@NonNull Application app) {
        // no-op
    }
}
