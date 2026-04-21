package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 100;
    private static final String[] PERMISSIONS = {
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnTestFFmpeg = findViewById(R.id.btn_test_ffmpeg);
        btnTestFFmpeg.setOnClickListener(v -> {
            if (checkPermissions()) {
                testFFmpeg();
            } else {
                requestPermissions();
            }
        });
    }

    private boolean checkPermissions() {
        for (String permission : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                testFFmpeg();
            } else {
                Toast.makeText(this, "需要存储权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void testFFmpeg() {
        File outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        String outputPath = new File(outputDir, "test.mp4").getAbsolutePath();
        
        Toast.makeText(this, "FFmpeg 版本信息查询中...", Toast.LENGTH_LONG).show();
        
        FFmpegHelper.cutVideo(
            "/sdcard/test.mp4",
            outputPath,
            0,
            10,
            new FFmpegHelper.FFmpegCallback() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "FFmpeg 执行成功! 输出: " + outputPath, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "FFmpeg 执行失败: " + message, Toast.LENGTH_LONG).show();
                    });
                }
            }
        );
    }
}
