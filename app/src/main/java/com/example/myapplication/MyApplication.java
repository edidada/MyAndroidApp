package com.example.myapplication;

import android.app.Application;

import com.didichuxing.doraemonkit.DoKit;
import com.example.myapplication.perf.GodEyeDashboard;
import com.example.myapplication.perf.MatrixAPM;
import com.example.myapplication.perf.PerfMarkers;

public class MyApplication extends Application {
    
    @Override
    public void onCreate() {
        super.onCreate();
        new DoKit.Builder(this).build();

        // APM: Matrix（免插桩子集）+ JankStats/Perfetto 标记
        PerfMarkers.markAppCreateStart();
        MatrixAPM.init(this, BuildConfig.DEBUG);

        // 实时看板：AndroidGodEye（debug 变体真装，release 变体是空实现）
        GodEyeDashboard.init(this);
    }
}
