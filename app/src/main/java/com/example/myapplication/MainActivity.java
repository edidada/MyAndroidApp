package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.utils.LogUtils;
import com.example.utils.ToastUtils;
import com.example.utils.StringUtils;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;
import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_PICK_VIDEO = 101;
    private static final int REQUEST_PICK_AUDIO = 102;
    private static final int REQUEST_MANAGE_STORAGE = 103;

    private PlayerView playerView;
    private ExoPlayer player;
    private TextView tvStatus;

    private String currentVideoPath;
    private String currentAudioPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.player_view);
        tvStatus = findViewById(R.id.tv_status);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        setupButtons();

        testUtils();

        if (!checkStoragePermission()) {
            requestStoragePermission();
        }
    }

    private void testUtils() {
        LogUtils.d("MainActivity", "utils 模块测试");
        String testStr = "hello world";
        LogUtils.d("StringUtils", "原始: " + testStr + ", 首字母大写: " + StringUtils.capitalize(testStr));
        ToastUtils.show(this, "utils 模块加载成功！");
    }

    private void setupButtons() {
        Button btnOpenVue = findViewById(R.id.btn_open_vue);
        Button btnPickVideo = findViewById(R.id.btn_pick_video);
        Button btnCutVideo = findViewById(R.id.btn_cut_video);
        Button btnExtractAudio = findViewById(R.id.btn_extract_audio);
        Button btnCompress = findViewById(R.id.btn_compress);
        Button btnRotate = findViewById(R.id.btn_rotate);
        Button btnAddAudio = findViewById(R.id.btn_add_audio);
        Button btnPickAudio = findViewById(R.id.btn_pick_audio);
        Button btnCutAudio = findViewById(R.id.btn_cut_audio);
        Button btnConvertMp4 = findViewById(R.id.btn_convert_mp4);
        Button btnConvertMp3 = findViewById(R.id.btn_convert_mp3);
        Button btnPlayStream = findViewById(R.id.btn_play_stream);
        Button btnTestLive = findViewById(R.id.btn_test_live);

        btnOpenVue.setOnClickListener(v -> openVuePage());

        btnPickVideo.setOnClickListener(v -> pickFile(REQUEST_PICK_VIDEO, "video/*"));
        btnCutVideo.setOnClickListener(v -> cutVideo());
        btnExtractAudio.setOnClickListener(v -> extractAudio());
        btnCompress.setOnClickListener(v -> compressVideo());
        btnRotate.setOnClickListener(v -> rotateVideo());
        btnAddAudio.setOnClickListener(v -> addAudioToVideo());
        btnPickAudio.setOnClickListener(v -> pickFile(REQUEST_PICK_AUDIO, "audio/*"));
        btnCutAudio.setOnClickListener(v -> cutAudio());
        btnConvertMp4.setOnClickListener(v -> convertToMp4());
        btnConvertMp3.setOnClickListener(v -> convertToMp3());
        btnPlayStream.setOnClickListener(v -> playStream());
        btnTestLive.setOnClickListener(v -> testLive());
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_PERMISSIONS);
        }
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
            if (!allGranted) {
                Toast.makeText(this, "需要存储权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = uri.getPath();
                if (requestCode == REQUEST_PICK_VIDEO) {
                    currentVideoPath = path;
                    tvStatus.setText("已选择视频: " + path);
                    playVideo(uri);
                } else if (requestCode == REQUEST_PICK_AUDIO) {
                    currentAudioPath = path;
                    tvStatus.setText("已选择音频: " + path);
                }
            }
        }
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "存储权限已获取", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "需要存储权限", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void pickFile(int requestCode, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(mimeType);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择文件"), requestCode);
    }

    private void playVideo(Uri uri) {
        MediaItem mediaItem = MediaItem.fromUri(uri);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    private void playVideo(String path) {
        MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(new File(path)));
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    private String getOutputDir() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        return dir != null ? dir.getAbsolutePath() : Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    private void showStatus(String message) {
        tvStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void cutVideo() {
        if (currentVideoPath == null) {
            showStatus("请先选择视频");
            return;
        }
        String outputPath = new File(getOutputDir(), "cut_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        showStatus("正在裁剪视频...");
        FFmpegHelper.cutVideo(currentVideoPath, outputPath, 0, 10, new FFmpegHelper.FFmpegCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    showStatus("裁剪成功: " + outputPath);
                    currentVideoPath = outputPath;
                    playVideo(outputPath);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> showStatus("裁剪失败: " + message));
            }
        });
    }

    private void extractAudio() {
        if (currentVideoPath == null) {
            showStatus("请先选择视频");
            return;
        }
        String outputPath = new File(getOutputDir(), "audio_" + System.currentTimeMillis() + ".mp3").getAbsolutePath();
        showStatus("正在提取音频...");
        FFmpegHelper.extractAudio(currentVideoPath, outputPath, new FFmpegHelper.FFmpegCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    showStatus("音频提取成功: " + outputPath);
                    currentAudioPath = outputPath;
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> showStatus("音频提取失败: " + message));
            }
        });
    }

    private void compressVideo() {
        if (currentVideoPath == null) {
            showStatus("请先选择视频");
            return;
        }
        String outputPath = new File(getOutputDir(), "compressed_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        showStatus("正在压缩视频...");
        FFmpegHelper.compressVideo(currentVideoPath, outputPath, 500, new FFmpegHelper.FFmpegCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    showStatus("压缩成功: " + outputPath);
                    currentVideoPath = outputPath;
                    playVideo(outputPath);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> showStatus("压缩失败: " + message));
            }
        });
    }

    private void rotateVideo() {
        if (currentVideoPath == null) {
            showStatus("请先选择视频");
            return;
        }
        String outputPath = new File(getOutputDir(), "rotated_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        showStatus("正在旋转视频...");
        FFmpegHelper.rotateVideo(currentVideoPath, outputPath, 90, new FFmpegHelper.FFmpegCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    showStatus("旋转成功: " + outputPath);
                    currentVideoPath = outputPath;
                    playVideo(outputPath);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> showStatus("旋转失败: " + message));
            }
        });
    }

    private void addAudioToVideo() {
        if (currentVideoPath == null || currentAudioPath == null) {
            showStatus("请先选择视频和音频");
            return;
        }
        String outputPath = new File(getOutputDir(), "video_with_audio_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        showStatus("正在添加音频...");
        FFmpegHelper.addAudioToVideo(currentVideoPath, currentAudioPath, outputPath, new FFmpegHelper.FFmpegCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    showStatus("添加音频成功: " + outputPath);
                    currentVideoPath = outputPath;
                    playVideo(outputPath);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> showStatus("添加音频失败: " + message));
            }
        });
    }

    private void cutAudio() {
        if (currentAudioPath == null) {
            showStatus("请先选择音频");
            return;
        }
        String outputPath = new File(getOutputDir(), "cut_audio_" + System.currentTimeMillis() + ".mp3").getAbsolutePath();
        showStatus("正在裁剪音频...");
        FFmpegHelper.cutAudio(currentAudioPath, outputPath, 0, 10, new FFmpegHelper.FFmpegCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    showStatus("音频裁剪成功: " + outputPath);
                    currentAudioPath = outputPath;
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> showStatus("音频裁剪失败: " + message));
            }
        });
    }

    private void convertToMp4() {
        if (currentVideoPath == null) {
            showStatus("请先选择视频");
            return;
        }
        String outputPath = new File(getOutputDir(), "converted_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        showStatus("正在转换格式...");
        FFmpegHelper.convertFormat(currentVideoPath, outputPath, new FFmpegHelper.FFmpegCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    showStatus("转换成功: " + outputPath);
                    currentVideoPath = outputPath;
                    playVideo(outputPath);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> showStatus("转换失败: " + message));
            }
        });
    }

    private void convertToMp3() {
        if (currentAudioPath == null && currentVideoPath == null) {
            showStatus("请先选择视频或音频");
            return;
        }
        String input = currentAudioPath != null ? currentAudioPath : currentVideoPath;
        String outputPath = new File(getOutputDir(), "converted_" + System.currentTimeMillis() + ".mp3").getAbsolutePath();
        showStatus("正在转换格式...");
        FFmpegHelper.convertFormat(input, outputPath, new FFmpegHelper.FFmpegCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    showStatus("转换成功: " + outputPath);
                    currentAudioPath = outputPath;
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> showStatus("转换失败: " + message));
            }
        });
    }

    private void playStream() {
        String liveUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
        showStatus("正在播放直播流...");
        MediaItem mediaItem = MediaItem.fromUri(liveUrl);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    private void testLive() {
        showStatus("直播功能: 可以使用 FFmpeg 推流到 RTMP 服务器");
        Toast.makeText(this, "需要配置 RTMP 服务器地址", Toast.LENGTH_LONG).show();
    }

    private void openVuePage() {
        Intent intent = new Intent(this, VueActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
