# FSV Native Uploader — Android Plugin

Нативний Android плагін для завантаження файлів на S3 з Unity у фоновому режимі через `ForegroundService` + `OkHttp`.

---

## Структура проекту

```
FsvUploader/                          ← Android Studio проект
  app/
    libs/
      unity-classes.jar               ← Unity bridge jar (з Unity Editor)
    src/main/java/com/farsightvision/uploader/
      UploadTask.java                 ← Модель завдання
      UploadProgress.java             ← Модель прогресу
      OkHttpUploader.java             ← HTTP завантажувач через OkHttp
      UploadService.java              ← Foreground Service
      UploadBridge.java               ← Точка входу з Unity
    build.gradle                      ← Gradle конфіг модуля
```

---

## Залежності (app/build.gradle)

```groovy
plugins {
    id 'com.android.library'
}

android {
    namespace 'com.farsightvision.uploader'
    compileSdk 36

    defaultConfig {
        minSdk 26
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly fileTree(dir: 'libs', include: ['*.jar'])
    implementation 'com.squareup.okhttp3:okhttp:5.3.2'
    implementation 'androidx.core:core:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
}
```

---

## unity-classes.jar

Знайди файл в Unity Editor:

**Windows:**
```
C:\Program Files\Unity\Hub\Editor\<version>\Editor\Data\PlaybackEngines\AndroidPlayer\Variations\mono\Release\Classes\classes.jar
```

**Mac:**
```
/Applications/Unity/Hub/Editor/<version>/PlaybackEngines/AndroidPlayer/Variations/mono/Release/Classes/classes.jar
```

Скопіюй в `app/libs/` як `unity-classes.jar`.

---

## Java файли

### UploadTask.java
```java
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
```

### UploadProgress.java
```java
package com.farsightvision.uploader;

public class UploadProgress {
    public String fileName;
    public long bytesUploaded;
    public long totalBytes;
    public boolean isComplete;
    public boolean isFailed;
    public String errorMessage;
    public String etag;
    public int partNumber;
}
```

### OkHttpUploader.java
```java
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
                byte[] buffer = new byte[1024 * 1024];
                int bytesRead;
                try {
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        sink.write(buffer, 0, bytesRead);
                        if (callback != null) callback.onProgress(bytesRead);
                    }
                } finally {
                    inputStream.close();
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
                throw new IOException("Upload failed: " + response.code() + " " + (response.body() != null ? response.body().string() : ""));
            }
        }
    }

    public void uploadMultipart(UploadTask task, ProgressCallback callback) throws IOException {
        Uri uri = Uri.parse(task.localPath);
        int partCount = task.parts.size();
        long chunkSize = task.fileSize / partCount;

        for (int i = 0; i < partCount; i++) {
            UploadTask.UploadPart part = task.parts.get(i);
            long offset = (long) i * chunkSize;
            long length = (i == partCount - 1) ? (task.fileSize - offset) : chunkSize;

            InputStream inputStream = contentResolver.openInputStream(uri);
            if (inputStream == null) throw new IOException("Cannot open: " + task.localPath);

            long skipped = inputStream.skip(offset);
            if (skipped != offset) throw new IOException("Cannot seek to offset: " + offset);

            byte[] chunk = readExact(inputStream, length);
            inputStream.close();

            RequestBody body = RequestBody.create(chunk, MediaType.parse(task.mimeType));

            Request request = new Request.Builder()
                .url(part.url)
                .put(body)
                .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Part " + part.partNumber + " upload failed: " + response.code());
                }
            }

            if (callback != null) callback.onProgress(chunk.length);
        }
    }

    private byte[] readExact(InputStream stream, long size) throws IOException {
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
```

### UploadService.java
```java
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class UploadService extends Service {

    private static final String CHANNEL_ID = "fsv_upload_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int MAX_PARALLEL = 3;

    private static final String UNITY_OBJECT = "UploadManager";
    private static final String UNITY_ON_PROGRESS = "OnUploadProgress";
    private static final String UNITY_ON_COMPLETE = "OnUploadComplete";
    private static final String UNITY_ON_ERROR = "OnUploadError";

    private ExecutorService executor;
    private static final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicLong totalBytesUploaded = new AtomicLong(0);
    private long totalBytes = 0;

    private static List<UploadTask> pendingTasks;
    private static String sessionUploadId;

    public static void startUpload(List<UploadTask> tasks, String uploadId) {
        pendingTasks = tasks;
        sessionUploadId = uploadId;
    }

    public static void cancel() {
        isCancelled.set(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isCancelled.set(false);
        totalBytesUploaded.set(0);
        totalBytes = 0;

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
        long total = totalBytes;

        executor.execute(() -> {
            for (UploadTask task : tasks) {
                if (isCancelled.get()) break;
                try { semaphore.acquire(); } catch (InterruptedException e) { break; }

                executor.execute(() -> {
                    try {
                        if (!isCancelled.get()) {
                            if (task.isMultipart) {
                                uploadMultipart(task, total);
                            } else {
                                uploadSimple(task, total);
                            }
                        }
                    } finally {
                        semaphore.release();
                    }
                });
            }

            try { semaphore.acquire(MAX_PARALLEL); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            if (!isCancelled.get()) {
                sendToUnity(UNITY_ON_COMPLETE, sessionUploadId);
            }
            stopSelf();
        });
    }

    private void uploadSimple(UploadTask task, long total) {
        try {
            OkHttpUploader uploader = new OkHttpUploader(getContentResolver());
            uploader.uploadSimple(task, bytes -> {
                long uploaded = totalBytesUploaded.addAndGet(bytes);
                int percent = (int)((float) uploaded / total * 100);
                updateNotification(percent);
                sendToUnity(UNITY_ON_PROGRESS, String.format(
                    "{\"fileName\":\"%s\",\"uploaded\":%d,\"total\":%d}",
                    task.fileName, uploaded, total));
            });
        } catch (Exception e) {
            sendToUnity(UNITY_ON_ERROR, String.format(
                "{\"fileName\":\"%s\",\"error\":\"%s\"}",
                task.fileName, e.getMessage()));
        }
    }

    private void uploadMultipart(UploadTask task, long total) {
        try {
            OkHttpUploader uploader = new OkHttpUploader(getContentResolver());
            uploader.uploadMultipart(task, bytes -> {
                long uploaded = totalBytesUploaded.addAndGet(bytes);
                int percent = (int)((float) uploaded / total * 100);
                updateNotification(percent);
                sendToUnity(UNITY_ON_PROGRESS, String.format(
                    "{\"fileName\":\"%s\",\"uploaded\":%d,\"total\":%d}",
                    task.fileName, uploaded, total));
            });
        } catch (Exception e) {
            sendToUnity(UNITY_ON_ERROR, String.format(
                "{\"fileName\":\"%s\",\"error\":\"%s\"}",
                task.fileName, e.getMessage()));
        }
    }

    private void sendToUnity(String method, String message) {
        try {
            UnityPlayer.UnitySendMessage(UNITY_OBJECT, method, message);
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(int percent) {
        createNotificationChannel();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FSV Upload")
            .setContentText(percent + "%")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(int percent) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(percent));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "File Upload", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }
}
```

### UploadBridge.java
```java
package com.farsightvision.uploader;

import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class UploadBridge {

    private static Context appContext;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static void startUpload(String tasksJson, String uploadId) {
        try {
            List<UploadTask> tasks = parseTasksJson(tasksJson);
            UploadService.startUpload(tasks, uploadId);
            Intent intent = new Intent(appContext, UploadService.class);
            appContext.startForegroundService(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void cancelUpload() {
        UploadService.cancel();
        if (appContext != null) {
            appContext.stopService(new Intent(appContext, UploadService.class));
        }
    }

    private static List<UploadTask> parseTasksJson(String json) throws Exception {
        List<UploadTask> tasks = new ArrayList<>();
        JSONArray array = new JSONArray(json);
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            UploadTask task = new UploadTask();
            task.localPath = obj.getString("localPath");
            task.presignedUrl = obj.getString("presignedUrl");
            task.mimeType = obj.getString("mimeType");
            task.fileName = obj.getString("fileName");
            task.fileSize = obj.getLong("fileSize");
            task.isMultipart = obj.getBoolean("isMultipart");
            if (task.isMultipart) {
                task.multipartUploadId = obj.getString("multipartUploadId");
                task.parts = new ArrayList<>();
                JSONArray partsArray = obj.getJSONArray("parts");
                for (int j = 0; j < partsArray.length(); j++) {
                    JSONObject partObj = partsArray.getJSONObject(j);
                    UploadTask.UploadPart part = new UploadTask.UploadPart();
                    part.url = partObj.getString("url");
                    part.partNumber = partObj.getInt("partNumber");
                    task.parts.add(part);
                }
            }
            tasks.add(task);
        }
        return tasks;
    }
}
```

---

## Збірка .aar

```
Build → Assemble Project
```

Готовий файл:
```
app/build/outputs/aar/app-debug.aar
```

---

## Інтеграція в Unity

### 1. Файли в Unity проекті

```
Assets/
  Plugins/
    Android/
      FsvUploader.aar          ← зібраний .aar
      AndroidManifest.xml      ← маніфест нижче
```

### 2. AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />

    <application>
        <service
            android:name="com.farsightvision.uploader.UploadService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>

</manifest>
```

### 3. UploadManager.cs

```csharp
using System;
using System.Collections.Generic;
using Newtonsoft.Json;
using UnityEngine;

namespace Plugins.Android
{
    public class UploadManager : MonoBehaviour
    {
        public static UploadManager Instance { get; private set; }

        public event Action<string, long, long> OnProgress;
        public event Action<string> OnComplete;
        public event Action<string, string> OnError;

        private void Awake()
        {
            if (Instance != null) { Destroy(gameObject); return; }
            Instance = this;
            DontDestroyOnLoad(gameObject);
            gameObject.name = "UploadManager";
            InitPlugin();
        }

        private void InitPlugin()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
            using var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
            using var bridge = new AndroidJavaClass("com.farsightvision.uploader.UploadBridge");
            bridge.CallStatic("init", activity);
#endif
        }

        public void StartUpload(List<UploadTaskData> tasks, string uploadId)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            var json = JsonConvert.SerializeObject(tasks);
            using var bridge = new AndroidJavaClass("com.farsightvision.uploader.UploadBridge");
            bridge.CallStatic("startUpload", json, uploadId);
#endif
        }

        public void CancelUpload()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using var bridge = new AndroidJavaClass("com.farsightvision.uploader.UploadBridge");
            bridge.CallStatic("cancelUpload");
#endif
        }

        // Викликається з Java через UnitySendMessage
        private void OnUploadProgress(string json)
        {
            var data = JsonConvert.DeserializeObject<ProgressData>(json);
            OnProgress?.Invoke(data.fileName, data.uploaded, data.total);
        }

        private void OnUploadComplete(string uploadId)
        {
            OnComplete?.Invoke(uploadId);
        }

        private void OnUploadError(string json)
        {
            var data = JsonConvert.DeserializeObject<ErrorData>(json);
            OnError?.Invoke(data.fileName, data.error);
        }
    }

    [Serializable]
    public class UploadTaskData
    {
        public string localPath;
        public string presignedUrl;
        public string mimeType;
        public string fileName;
        public long fileSize;
        public bool isMultipart;
        public string multipartUploadId;
        public List<UploadPartData> parts;
    }

    [Serializable]
    public class UploadPartData
    {
        public string url;
        public int partNumber;
    }

    [Serializable]
    public class ProgressData
    {
        public string fileName;
        public long uploaded;
        public long total;
    }

    [Serializable]
    public class ErrorData
    {
        public string fileName;
        public string error;
    }
}
```

### 4. Використання в ScanUploadsProcessor

Додай `UploadManager` на GameObject в стартовій сцені. Підпишись на події:

```csharp
UploadManager.Instance.OnProgress += (fileName, uploaded, total) => {
    NotifyUploadProgress(uploadId, uploaded, total, false);
};

UploadManager.Instance.OnComplete += (uploadId) => {
    // завантаження завершено
};

UploadManager.Instance.OnError += (fileName, error) => {
    Log.Error(LogTag.Uploads, $"Upload error for {fileName}: {error}");
};
```

Запуск завантаження:

```csharp
var tasks = scanUploadModalPresenter.Files.Select(kvp => new UploadTaskData
{
    localPath = kvp.Value,
    presignedUrl = s3Response.url,
    mimeType = kvp.Key.file_content_type,
    fileName = kvp.Key.file_name,
    fileSize = kvp.Key.file_size,
    isMultipart = false
}).ToList();

UploadManager.Instance.StartUpload(tasks, uploadId);
```

---

## Статус

- [x] Базова архітектура
- [x] Simple upload через OkHttp
- [x] Multipart upload (базова реалізація)
- [x] Foreground Service з нотифікацією
- [x] Паралельне завантаження (3 файли одночасно)
- [x] Callbacks в Unity через UnitySendMessage
- [ ] ETag повернення для multipart (потребує доопрацювання)
- [ ] Інтеграція з ScanUploadsProcessor (потребує доопрацювання)
- [ ] Тестування на реальному девайсі
