package com.example.myapplication;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Session;

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

    public static void cutAudio(String inputPath, String outputPath, int startTime, int duration, FFmpegCallback callback) {
        String command = String.format(
            "-y -ss %d -i \"%s\" -t %d -c:a copy \"%s\"",
            startTime, inputPath, duration, outputPath
        );
        executeCommand(command, callback);
    }

    public static void convertFormat(String inputPath, String outputPath, FFmpegCallback callback) {
        String command = String.format("-y -i \"%s\" \"%s\"", inputPath, outputPath);
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

    public static void mergeVideos(String[] inputPaths, String outputPath, FFmpegCallback callback) {
        StringBuilder list = new StringBuilder();
        for (String path : inputPaths) {
            list.append("file '").append(path).append("'\\n");
        }
        String command = String.format(
            "-y -f concat -safe 0 -i \"concat:%s\" -c copy \"%s\"",
            list, outputPath
        );
        executeCommand(command, callback);
    }

    public static void addAudioToVideo(String videoPath, String audioPath, String outputPath, FFmpegCallback callback) {
        String command = String.format(
            "-y -i \"%s\" -i \"%s\" -c:v copy -c:a aac -map 0:v:0 -map 1:a:0 \"%s\"",
            videoPath, audioPath, outputPath
        );
        executeCommand(command, callback);
    }

    public static void rotateVideo(String inputPath, String outputPath, int degrees, FFmpegCallback callback) {
        String transpose;
        if (degrees == 90) {
            transpose = "transpose=1";
        } else if (degrees == 180) {
            transpose = "transpose=2,transpose=2";
        } else if (degrees == 270) {
            transpose = "transpose=2";
        } else {
            transpose = "";
        }
        String command = String.format(
            "-y -i \"%s\" -vf \"%s\" -c:a copy \"%s\"",
            inputPath, transpose, outputPath
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
