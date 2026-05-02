package com.micklab.resource_mon;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class MonitorStore {
    private static final String PREFS_NAME = "monitor_store";
    private static final String KEY_MONITORING_ENABLED = "monitoring_enabled";
    private static final String KEY_LATEST_SAMPLE = "latest_sample";
    private static final String KEY_SAMPLE_HISTORY = "sample_history";
    private static final int MAX_HISTORY_SIZE = 240;

    private static volatile MonitorStore instance;

    public static MonitorStore get(Context context) {
        if (instance == null) {
            synchronized (MonitorStore.class) {
                if (instance == null) {
                    instance = new MonitorStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final SharedPreferences sharedPreferences;
    private final Object lock = new Object();

    private MonitorStore(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void setMonitoringEnabled(boolean enabled) {
        sharedPreferences.edit()
                .putBoolean(KEY_MONITORING_ENABLED, enabled)
                .apply();
    }

    public boolean isMonitoringEnabled() {
        return sharedPreferences.getBoolean(KEY_MONITORING_ENABLED, false);
    }

    public void appendSample(MetricsSampler.MetricsSnapshot snapshot) {
        synchronized (lock) {
            JSONObject serialized = serializeSnapshot(snapshot);
            JSONArray history = readArray(sharedPreferences.getString(KEY_SAMPLE_HISTORY, "[]"));
            history.put(serialized);

            if (history.length() > MAX_HISTORY_SIZE) {
                JSONArray trimmed = new JSONArray();
                int start = history.length() - MAX_HISTORY_SIZE;
                for (int index = start; index < history.length(); index++) {
                    Object entry = history.opt(index);
                    if (entry != null) {
                        trimmed.put(entry);
                    }
                }
                history = trimmed;
            }

            sharedPreferences.edit()
                    .putString(KEY_LATEST_SAMPLE, serialized.toString())
                    .putString(KEY_SAMPLE_HISTORY, history.toString())
                    .apply();
        }
    }

    public MetricsSampler.MetricsSnapshot getLatestSample() {
        synchronized (lock) {
            String raw = sharedPreferences.getString(KEY_LATEST_SAMPLE, null);
            if (raw == null) {
                return null;
            }
            return deserializeSnapshot(readObject(raw));
        }
    }

    public List<MetricsSampler.MetricsSnapshot> getRecentSamples() {
        synchronized (lock) {
            JSONArray history = readArray(sharedPreferences.getString(KEY_SAMPLE_HISTORY, "[]"));
            List<MetricsSampler.MetricsSnapshot> snapshots = new ArrayList<>();
            for (int index = 0; index < history.length(); index++) {
                JSONObject object = history.optJSONObject(index);
                MetricsSampler.MetricsSnapshot snapshot = deserializeSnapshot(object);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
            return snapshots;
        }
    }

    private JSONObject serializeSnapshot(MetricsSampler.MetricsSnapshot snapshot) {
        JSONObject object = new JSONObject();
        try {
            object.put("timestampMillis", snapshot.timestampMillis);
            object.put("cpuUsagePercent", snapshot.cpuUsagePercent);
            object.put("cpuAverageMhz", snapshot.cpuAverageMhz);
            object.put("cpuMaxMhz", snapshot.cpuMaxMhz);
            object.put("ramUsedMb", snapshot.ramUsedMb);
            object.put("ramTotalMb", snapshot.ramTotalMb);
            object.put("storageFreeMb", snapshot.storageFreeMb);
            object.put("storageTotalMb", snapshot.storageTotalMb);
            object.put("networkBytesPerSec", snapshot.networkBytesPerSec);
            object.put("networkMaxBytesPerSec", snapshot.networkMaxBytesPerSec);
            object.put("memoryDetails", serializeMemoryDetails(snapshot.memoryDetails));
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to serialize sample", exception);
        }
        return object;
    }

    private JSONObject serializeMemoryDetails(MetricsSampler.MemoryDetails memoryDetails) throws JSONException {
        JSONObject object = new JSONObject();
        if (memoryDetails == null) {
            return object;
        }
        object.put("totalBytes", memoryDetails.totalBytes);
        object.put("usedBytes", memoryDetails.usedBytes);
        object.put("availableBytes", memoryDetails.availableBytes);
        object.put("freeBytes", memoryDetails.freeBytes);
        object.put("buffersBytes", memoryDetails.buffersBytes);
        object.put("cachedBytes", memoryDetails.cachedBytes);
        object.put("slabBytes", memoryDetails.slabBytes);
        object.put("reclaimableSlabBytes", memoryDetails.reclaimableSlabBytes);
        object.put("unreclaimableSlabBytes", memoryDetails.unreclaimableSlabBytes);
        object.put("activeBytes", memoryDetails.activeBytes);
        object.put("inactiveBytes", memoryDetails.inactiveBytes);
        object.put("shmemBytes", memoryDetails.shmemBytes);
        object.put("swapTotalBytes", memoryDetails.swapTotalBytes);
        object.put("swapFreeBytes", memoryDetails.swapFreeBytes);
        object.put("swapCachedBytes", memoryDetails.swapCachedBytes);
        return object;
    }

    private MetricsSampler.MetricsSnapshot deserializeSnapshot(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new MetricsSampler.MetricsSnapshot(
                object.optLong("timestampMillis", 0L),
                object.optDouble("cpuUsagePercent", 0d),
                object.optDouble("cpuAverageMhz", 0d),
                object.optDouble("cpuMaxMhz", 0d),
                object.optLong("ramUsedMb", 0L),
                object.optLong("ramTotalMb", 0L),
                deserializeMemoryDetails(object.optJSONObject("memoryDetails")),
                object.optLong("storageFreeMb", 0L),
                object.optLong("storageTotalMb", 0L),
                object.optLong("networkBytesPerSec", 0L),
                object.optLong("networkMaxBytesPerSec", 0L));
    }

    private MetricsSampler.MemoryDetails deserializeMemoryDetails(JSONObject object) {
        if (object == null) {
            return new MetricsSampler.MemoryDetails(
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        return new MetricsSampler.MemoryDetails(
                object.optLong("totalBytes", 0L),
                object.optLong("usedBytes", 0L),
                object.optLong("availableBytes", 0L),
                object.optLong("freeBytes", 0L),
                object.optLong("buffersBytes", 0L),
                object.optLong("cachedBytes", 0L),
                object.optLong("slabBytes", 0L),
                object.optLong("reclaimableSlabBytes", 0L),
                object.optLong("unreclaimableSlabBytes", 0L),
                object.optLong("activeBytes", 0L),
                object.optLong("inactiveBytes", 0L),
                object.optLong("shmemBytes", 0L),
                object.optLong("swapTotalBytes", 0L),
                object.optLong("swapFreeBytes", 0L),
                object.optLong("swapCachedBytes", 0L));
    }

    private JSONArray readArray(String raw) {
        try {
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (JSONException exception) {
            return new JSONArray();
        }
    }

    private JSONObject readObject(String raw) {
        try {
            return new JSONObject(raw);
        } catch (JSONException exception) {
            return null;
        }
    }
}
