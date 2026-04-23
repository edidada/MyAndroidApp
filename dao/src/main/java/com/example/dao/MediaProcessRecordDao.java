package com.example.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

@Dao
public interface MediaProcessRecordDao {

    @Insert
    long insert(MediaProcessRecord record);

    @Query("SELECT * FROM media_process_records ORDER BY createdAt DESC LIMIT 1")
    MediaProcessRecord getLatestRecord();

    @Query("SELECT COUNT(*) FROM media_process_records")
    int getRecordCount();

    @Query("DELETE FROM media_process_records")
    void clearAll();
}
