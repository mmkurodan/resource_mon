package com.micklab.rm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MonitorStore {
    private static final String PREFS_NAME = "monitor_store";
    private static final String KEY_MONITORING_ENABLED = "monitoring_enabled";
    private static final String KEY_LATEST_SAMPLE = "latest_sample";
    private static final String KEY_SAMPLE_HISTORY = "sample_history";
    private static final String KEY_FOREGROUND_PROCESS_HISTORY = "foreground_process_history";
    private static final int MAX_HISTORY_SIZE = 240;
    private static final int MAX_FOREGROUND_PROCESS_HISTORY_SIZE = 40;

    private static volatile MonitorStore instance;

    public static final class ForegroundProcessStats {
        public final String label;
        public final String processName;
        public final String packageSummary;
        public final int sampleCount;
        public final int cpuSampleCount;
        public final int memorySampleCount;
        public final double averageCpuPercent;
        public final long averageMemoryKb;
        public final long lastSeenTimestamp;

        ForegroundProcessStats(
                String label,
                String processName,
                String packageSummary,
                int sampleCount,
                int cpuSampleCount,
                int memorySampleCount,
                double averageCpuPercent,
                long averageMemoryKb,
                long lastSeenTimestamp) {
            this.label = label;
            this.processName = processName;
            this.packageSummary = packageSummary;
            this.sampleCount = sampleCount;
            this.cpuSampleCount = cpuSampleCount;
            this.memorySampleCount = memorySampleCount;
            this.averageCpuPercent = averageCpuPercent;
            this.averageMemoryKb = averageMemoryKb;
            this.lastSeenTimestamp = lastSeenTimestamp;
        }
    }

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

    public void recordForegroundProcessSnapshot(
            long timestampMillis,
            List<ProcessInspector.ProcessEntry> processEntries) {
        synchronized (lock) {
            Map<String, ForegroundProcessStats> statsByProcess = new HashMap<>();
            JSONArray history = readArray(sharedPreferences.getString(KEY_FOREGROUND_PROCESS_HISTORY, "[]"));
            for (int index = 0; index < history.length(); index++) {
                ForegroundProcessStats stats = deserializeForegroundProcessStats(history.optJSONObject(index));
                if (stats != null) {
                    statsByProcess.put(stats.processName, stats);
                }
            }

            for (ProcessInspector.ProcessEntry processEntry : processEntries) {
                if (processEntry == null || !processEntry.foregroundLike) {
                    continue;
                }
                ForegroundProcessStats current = statsByProcess.get(processEntry.processName);
                int nextSampleCount = current == null ? 1 : current.sampleCount + 1;
                int nextCpuSampleCount = current == null ? 0 : current.cpuSampleCount;
                int nextMemorySampleCount = current == null ? 0 : current.memorySampleCount;
                double cpuTotal = current == null ? 0d : current.averageCpuPercent * current.cpuSampleCount;
                long memoryTotalKb = current == null ? 0L : current.averageMemoryKb * (long) current.memorySampleCount;

                if (processEntry.hasCpuSample()) {
                    cpuTotal += processEntry.cpuSortValue;
                    nextCpuSampleCount += 1;
                }
                if (processEntry.hasMemorySample()) {
                    memoryTotalKb += processEntry.memorySortValue;
                    nextMemorySampleCount += 1;
                }

                statsByProcess.put(processEntry.processName, new ForegroundProcessStats(
                        processEntry.label,
                        processEntry.processName,
                        processEntry.packageSummary,
                        nextSampleCount,
                        nextCpuSampleCount,
                        nextMemorySampleCount,
                        nextCpuSampleCount > 0 ? cpuTotal / nextCpuSampleCount : 0d,
                        nextMemorySampleCount > 0
                                ? Math.round(memoryTotalKb / (double) nextMemorySampleCount)
                                : 0L,
                        timestampMillis));
            }

            List<ForegroundProcessStats> sortedStats = new ArrayList<>(statsByProcess.values());
            Collections.sort(sortedStats, new Comparator<ForegroundProcessStats>() {
                @Override
                public int compare(ForegroundProcessStats left, ForegroundProcessStats right) {
                    int timeCompare = Long.compare(right.lastSeenTimestamp, left.lastSeenTimestamp);
                    if (timeCompare != 0) {
                        return timeCompare;
                    }
                    return Integer.compare(right.sampleCount, left.sampleCount);
                }
            });
            if (sortedStats.size() > MAX_FOREGROUND_PROCESS_HISTORY_SIZE) {
                sortedStats = new ArrayList<>(sortedStats.subList(0, MAX_FOREGROUND_PROCESS_HISTORY_SIZE));
            }

            JSONArray serialized = new JSONArray();
            for (ForegroundProcessStats stats : sortedStats) {
                serialized.put(serializeForegroundProcessStats(stats));
            }
            sharedPreferences.edit()
                    .putString(KEY_FOREGROUND_PROCESS_HISTORY, serialized.toString())
                    .apply();
        }
    }

    public List<ForegroundProcessStats> getForegroundProcessHistory() {
        synchronized (lock) {
            JSONArray history = readArray(sharedPreferences.getString(KEY_FOREGROUND_PROCESS_HISTORY, "[]"));
            List<ForegroundProcessStats> stats = new ArrayList<>();
            for (int index = 0; index < history.length(); index++) {
                ForegroundProcessStats entry = deserializeForegroundProcessStats(history.optJSONObject(index));
                if (entry != null) {
                    stats.add(entry);
                }
            }
            return stats;
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

    private JSONObject serializeForegroundProcessStats(ForegroundProcessStats stats) {
        JSONObject object = new JSONObject();
        try {
            object.put("label", stats.label);
            object.put("processName", stats.processName);
            object.put("packageSummary", stats.packageSummary);
            object.put("sampleCount", stats.sampleCount);
            object.put("cpuSampleCount", stats.cpuSampleCount);
            object.put("memorySampleCount", stats.memorySampleCount);
            object.put("averageCpuPercent", stats.averageCpuPercent);
            object.put("averageMemoryKb", stats.averageMemoryKb);
            object.put("lastSeenTimestamp", stats.lastSeenTimestamp);
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to serialize foreground process stats", exception);
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
        object.put("committedBytes", memoryDetails.committedBytes);
        object.put("commitLimitBytes", memoryDetails.commitLimitBytes);
        object.put("vmallocUsedBytes", memoryDetails.vmallocUsedBytes);
        object.put("vmallocTotalBytes", memoryDetails.vmallocTotalBytes);
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
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
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
                object.optLong("swapCachedBytes", 0L),
                object.optLong("committedBytes", 0L),
                object.optLong("commitLimitBytes", 0L),
                object.optLong("vmallocUsedBytes", 0L),
                object.optLong("vmallocTotalBytes", 0L));
    }

    private ForegroundProcessStats deserializeForegroundProcessStats(JSONObject object) {
        if (object == null) {
            return null;
        }
        String processName = object.optString("processName", null);
        if (processName == null || processName.trim().isEmpty()) {
            return null;
        }
        return new ForegroundProcessStats(
                object.optString("label", processName),
                processName,
                object.optString("packageSummary", processName),
                object.optInt("sampleCount", 0),
                object.optInt("cpuSampleCount", 0),
                object.optInt("memorySampleCount", 0),
                object.optDouble("averageCpuPercent", 0d),
                object.optLong("averageMemoryKb", 0L),
                object.optLong("lastSeenTimestamp", 0L));
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
