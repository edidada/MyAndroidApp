package com.example.dao;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "media_process_records")
public class MediaProcessRecord {

    @PrimaryKey(autoGenerate = true)
    private long id;
    private String operationType;
    private String inputPath;
    private String outputPath;
    private long createdAt;

    public MediaProcessRecord(String operationType, String inputPath, String outputPath, long createdAt) {
        this.operationType = operationType;
        this.inputPath = inputPath;
        this.outputPath = outputPath;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getInputPath() {
        return inputPath;
    }

    public void setInputPath(String inputPath) {
        this.inputPath = inputPath;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
