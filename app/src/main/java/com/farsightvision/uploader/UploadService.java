package com.farsightvision.uploader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.unity3d.player.UnityPlayer;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

public class UploadService extends Service {

    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int MAX_PARALLEL = 3;

    // Unity GameObject і метод для callback
    private static final String UNITY_OBJECT = "UploadManager";
    private static final String UNITY_ON_PROGRESS = "OnUploadProgress";
    private static final String UNITY_ON_COMPLETE = "OnUploadComplete";
    private static final String UNITY_ON_ERROR = "OnUploadError";

    private ExecutorService executor;
    private AtomicBoolean isCancelled = new AtomicBoolean(false);
    private AtomicLong totalBytesUploaded = new AtomicLong(0);
    private long totalBytes = 0;

    // Статичний список тасків — передається з Unity
    private static List<UploadTask> pendingTasks;
    private static String sessionUploadId;

    public static void startUpload(List<UploadTask> tasks, String uploadId) {
        pendingTasks = tasks;
        sessionUploadId = uploadId;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isCancelled.set(false);
        totalBytesUploaded.set(0);

        startForeground(NOTIFICATION_ID, buildNotification(0));
        executor = Executors.newFixedThreadPool(MAX_PARALLEL + 1);

        if (pendingTasks != null) {
            for (UploadTask task : pendingTasks) {
                totalBytes += task.fileSize;
            }
            startUploading(pendingTasks);
        }

        return START_NOT_STICKY;
    }

    private void startUploading(List<UploadTask> tasks) {
        Semaphore semaphore = new Semaphore(MAX_PARALLEL);

        executor.execute(() -> {
            for (UploadTask task : tasks) {
                if (isCancelled.get()) break;

                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    break;
                }

                executor.execute(() -> {
                    try {
                        if (!isCancelled.get()) {
                            if (task.isMultipart) {
                                uploadMultipart(task);
                            } else {
                                uploadSimple(task);
                            }
                        }
                    } finally {
                        semaphore.release();
                    }
                });
            }

            // Чекаємо поки всі семафори звільняться
            try {
                semaphore.acquire(MAX_PARALLEL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (!isCancelled.get()) {
                sendToUnity(UNITY_ON_COMPLETE, sessionUploadId);
            }

            stopSelf();
        });
    }

    private void uploadSimple(UploadTask task) {
        try {
            OkHttpUploader uploader = new OkHttpUploader(getContentResolver());
            uploader.uploadSimple(task, (bytesUploaded) -> {
                long total = totalBytesUploaded.addAndGet(bytesUploaded);
                int percent = (int)((float) total / totalBytes * 100);
                updateNotification(percent);

                String progressJson = String.format(
                        "{\"fileName\":\"%s\",\"uploaded\":%d,\"total\":%d}",
                        task.fileName, total, totalBytes
                );
                sendToUnity(UNITY_ON_PROGRESS, progressJson);
            });
        } catch (Exception e) {
            String errorJson = String.format(
                    "{\"fileName\":\"%s\",\"error\":\"%s\"}",
                    task.fileName, e.getMessage()
            );
            sendToUnity(UNITY_ON_ERROR, errorJson);
        }
    }

    private void uploadMultipart(UploadTask task) {
        try {
            OkHttpUploader uploader = new OkHttpUploader(getContentResolver());
            uploader.uploadMultipart(task, (bytesUploaded) -> {
                long total = totalBytesUploaded.addAndGet(bytesUploaded);
                int percent = (int)((float) total / totalBytes * 100);
                updateNotification(percent);

                String progressJson = String.format(
                        "{\"fileName\":\"%s\",\"uploaded\":%d,\"total\":%d}",
                        task.fileName, total, totalBytes
                );
                sendToUnity(UNITY_ON_PROGRESS, progressJson);
            });
        } catch (Exception e) {
            String errorJson = String.format(
                    "{\"fileName\":\"%s\",\"error\":\"%s\"}",
                    task.fileName, e.getMessage()
            );
            sendToUnity(UNITY_ON_ERROR, errorJson);
        }
    }

    public static void cancel() {
        // викликається з Unity
    }

    private void sendToUnity(String method, String message) {
        UnityPlayer.UnitySendMessage(UNITY_OBJECT, method, message);
    }

    private Notification buildNotification(int percent) {
        createNotificationChannel();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Uploading files")
                .setContentText(percent + "%")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setProgress(100, percent, percent == 0)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(int percent) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(percent));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "File Upload", NotificationManager.IMPORTANCE_LOW
        );
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }
}