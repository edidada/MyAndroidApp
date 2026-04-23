package com.example.dao;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaProcessRepository {

    public interface ResultCallback<T> {
        void onResult(T result);
    }

    private static volatile MediaProcessRepository INSTANCE;

    private final MediaProcessRecordDao recordDao;
    private final ExecutorService ioExecutor;
    private final Handler mainHandler;

    private MediaProcessRepository(Context context) {
        this.recordDao = MediaDatabase.getInstance(context).mediaProcessRecordDao();
        this.ioExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static MediaProcessRepository getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (MediaProcessRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MediaProcessRepository(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    public void saveRecord(String operationType, String inputPath, String outputPath) {
        ioExecutor.execute(() -> recordDao.insert(new MediaProcessRecord(
                operationType,
                inputPath,
                outputPath,
                System.currentTimeMillis()
        )));
    }

    public void getLatestRecord(ResultCallback<MediaProcessRecord> callback) {
        ioExecutor.execute(() -> {
            MediaProcessRecord record = recordDao.getLatestRecord();
            mainHandler.post(() -> callback.onResult(record));
        });
    }

    public void getRecordCount(ResultCallback<Integer> callback) {
        ioExecutor.execute(() -> {
            Integer count = recordDao.getRecordCount();
            mainHandler.post(() -> callback.onResult(count));
        });
    }

    public void clearAll(ResultCallback<Boolean> callback) {
        ioExecutor.execute(() -> {
            recordDao.clearAll();
            mainHandler.post(() -> callback.onResult(Boolean.TRUE));
        });
    }
}
