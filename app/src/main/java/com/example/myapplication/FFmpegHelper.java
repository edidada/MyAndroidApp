package com.example.myapplication;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Session;
import com.arthenica.ffmpegkit.SessionState;

public class FFmpegHelper {

    public interface FFmpegCallback {
        void onSuccess();
        void onError(String message);
    }

    public static void cutVideo(String inputPath, String outputPath, int startTime, int duration, FFmpegCallback callback) {
        String command = String.format(
            "-y -ss %d -i \"%s\" -t %d -c:v copy -c:a copy \"%s\"",
            startTime, inputPath, duration, outputPath
        );
        executeCommand(command, callback);
    }

    public static void extractAudio(String inputPath, String outputPath, FFmpegCallback callback) {
        String command = String.format(
            "-y -i \"%s\" -vn -acodec libmp3lame -q:a 2 \"%s\"",
            inputPath, outputPath
        );
        executeCommand(command, callback);
    }

    public static void compressVideo(String inputPath, String outputPath, int bitrate, FFmpegCallback callback) {
        String command = String.format(
            "-y -i \"%s\" -b:v %dk -c:v libx264 -c:a aac \"%s\"",
            inputPath, bitrate, outputPath
        );
        executeCommand(command, callback);
    }

    public static void addWatermark(String inputPath, String outputPath, String watermarkPath, FFmpegCallback callback) {
        String command = String.format(
            "-y -i \"%s\" -i \"%s\" -filter_complex \"overlay=10:10\" -c:a copy \"%s\"",
            inputPath, watermarkPath, outputPath
        );
        executeCommand(command, callback);
    }

    private static void executeCommand(String command, FFmpegCallback callback) {
        new Thread(() -> {
            Session session = FFmpegKit.execute(command);
            if (ReturnCode.isSuccess(session.getReturnCode())) {
                if (callback != null) {
                    callback.onSuccess();
                }
            } else {
                if (callback != null) {
                    callback.onError(session.getAllLogsAsString());
                }
            }
        }).start();
    }
}
