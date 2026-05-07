package com.farsightvision.uploader;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

public class OkHttpUploader {

    private final ContentResolver contentResolver;
    private final OkHttpClient client;

    public interface ProgressCallback {
        void onProgress(long bytesUploaded);
    }

    public OkHttpUploader(ContentResolver contentResolver) {
        this.contentResolver = contentResolver;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.MINUTES)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void uploadSimple(UploadTask task, ProgressCallback callback) throws IOException {
        Uri uri = Uri.parse(task.localPath);
        InputStream inputStream = contentResolver.openInputStream(uri);
        if (inputStream == null) throw new IOException("Cannot open: " + task.localPath);

        RequestBody body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse(task.mimeType);
            }

            @Override
            public long contentLength() {
                return task.fileSize;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                byte[] buffer = new byte[1024 * 1024]; // 1MB chunks
                int bytesRead;
                long totalSent = 0;

                try (Source source = Okio.source(inputStream)) {
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        sink.write(buffer, 0, bytesRead);
                        totalSent += bytesRead;
                        if (callback != null) callback.onProgress(bytesRead);
                    }
                }
            }
        };

        Request request = new Request.Builder()
                .url(task.presignedUrl)
                .put(body)
                .addHeader("Content-Type", task.mimeType)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Upload failed: " + response.code() + " " + response.body().string());
            }
        }
    }

    public void uploadMultipart(UploadTask task, ProgressCallback callback) throws IOException {
        Uri uri = Uri.parse(task.localPath);

        for (UploadTask.UploadPart part : task.parts) {
            InputStream inputStream = contentResolver.openInputStream(uri);
            if (inputStream == null) throw new IOException("Cannot open: " + task.localPath);

            // TODO: seek до потрібного offset для кожного чанку
            // Поки що базова реалізація

            RequestBody body = RequestBody.create(
                    readChunk(inputStream, task.fileSize / task.parts.size()),
                    MediaType.parse(task.mimeType)
            );

            Request request = new Request.Builder()
                    .url(part.url)
                    .put(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Part upload failed: " + response.code());
                }
                // ETag з response.header("ETag") треба повернути в Unity
            }

            if (callback != null) callback.onProgress(task.fileSize / task.parts.size());
            inputStream.close();
        }
    }

    private byte[] readChunk(InputStream stream, long size) throws IOException {
        byte[] buffer = new byte[(int) size];
        int bytesRead = 0;
        while (bytesRead < size) {
            int read = stream.read(buffer, bytesRead, (int)(size - bytesRead));
            if (read == -1) break;
            bytesRead += read;
        }
        return buffer;
    }
}