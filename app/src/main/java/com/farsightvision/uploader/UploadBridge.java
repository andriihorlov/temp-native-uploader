package com.farsightvision.uploader;

import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class UploadBridge {

    private static Context appContext;

    // Викликається з Unity для ініціалізації
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    // Викликається з Unity для старту завантаження
    // tasksJson - JSON масив з завданнями
    // uploadId - ID сесії завантаження
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

    // Викликається з Unity для скасування
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