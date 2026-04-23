package com.example.dao;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {MediaProcessRecord.class}, version = 1, exportSchema = false)
public abstract class MediaDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "media_history.db";
    private static volatile MediaDatabase INSTANCE;

    public abstract MediaProcessRecordDao mediaProcessRecordDao();

    public static MediaDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (MediaDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            MediaDatabase.class,
                            DATABASE_NAME
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}
