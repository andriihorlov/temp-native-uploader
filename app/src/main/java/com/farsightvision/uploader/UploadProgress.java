package com.farsightvision.uploader;

public class UploadProgress {
    public String fileName;
    public long bytesUploaded;
    public long totalBytes;
    public boolean isComplete;
    public boolean isFailed;
    public String errorMessage;
    public String etag; // для multipart
    public int partNumber; // для multipart
}