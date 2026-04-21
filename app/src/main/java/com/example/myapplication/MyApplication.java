package com.example.myapplication;

import android.app.Application;
import com.didichuxing.doraemonkit.DoKit;

public class MyApplication extends Application {
    
    @Override
    public void onCreate() {
        super.onCreate();
        new DoKit.Builder(this).build();
    }
}
