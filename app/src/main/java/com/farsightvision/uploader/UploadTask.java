package com.farsightvision.uploader;

import java.util.List;

public class UploadTask {
    public String localPath;
    public String presignedUrl;
    public String mimeType;
    public String fileName;
    public boolean isMultipart;
    public List<UploadPart> parts;
    public String multipartUploadId;
    public long fileSize;

    public static class UploadPart {
        public String url;
        public int partNumber;
    }
}