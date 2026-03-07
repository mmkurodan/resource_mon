package com.micklab.resource_mon;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MetricsSampler {
    private static final String CPU_SYSFS_ROOT = "/sys/devices/system/cpu";

    public interface Listener {
        void onSample(MetricsSnapshot snapshot);
    }

    public static final class MetricsSnapshot {
        public final long timestampMillis;
        public final long cpuAverageHz;
        public final long cpuMaxHz;
        public final long ramUsedMb;
        public final long ramTotalMb;
        public final long storageFreeMb;
        public final long storageTotalMb;
        public final long networkBytesPerSec;
        public final long networkMaxBytesPerSec;

        MetricsSnapshot(
                long timestampMillis,
                long cpuAverageHz,
                long cpuMaxHz,
                long ramUsedMb,
                long ramTotalMb,
                long storageFreeMb,
                long storageTotalMb,
                long networkBytesPerSec,
                long networkMaxBytesPerSec) {
            this.timestampMillis = timestampMillis;
            this.cpuAverageHz = cpuAverageHz;
            this.cpuMaxHz = cpuMaxHz;
            this.ramUsedMb = ramUsedMb;
            this.ramTotalMb = ramTotalMb;
            this.storageFreeMb = storageFreeMb;
            this.storageTotalMb = storageTotalMb;
            this.networkBytesPerSec = networkBytesPerSec;
            this.networkMaxBytesPerSec = networkMaxBytesPerSec;
        }
    }

    private final Context context;
    private final long intervalMs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private Thread workerThread;
    private long lastRxBytes = TrafficStats.getTotalRxBytes();
    private long lastTxBytes = TrafficStats.getTotalTxBytes();

    public MetricsSampler(Context context, long intervalMs) {
        this.context = context.getApplicationContext();
        this.intervalMs = intervalMs;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    final MetricsSnapshot snapshot = sample();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            for (Listener listener : listeners) {
                                listener.onSample(snapshot);
                            }
                        }
                    });
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "metrics-sampler");
        workerThread.start();
    }

    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private MetricsSnapshot sample() {
        long[] cpuFrequency = readCpuAverageFrequencyHz();
        long[] memory = readMemoryMb();
        long[] storage = readStorageMb();
        long[] network = readNetworkBytesPerSecond();
        long networkBytesPerSec = Math.max(0L, network[0]) + Math.max(0L, network[1]);
        long networkMaxBytesPerSec = readNetworkMaxBytesPerSecond();
        return new MetricsSnapshot(
                System.currentTimeMillis(),
                cpuFrequency[0],
                cpuFrequency[1],
                memory[0],
                memory[1],
                storage[0],
                storage[1],
                networkBytesPerSec,
                networkMaxBytesPerSec);
    }

    private long[] readCpuAverageFrequencyHz() {
        File cpuRoot = new File(CPU_SYSFS_ROOT);
        File[] cpuEntries = cpuRoot.listFiles();
        if (cpuEntries == null || cpuEntries.length == 0) {
            return new long[]{0L, 0L};
        }

        long currentHzSum = 0L;
        long maxHzSum = 0L;
        int currentCount = 0;
        int maxCount = 0;

        for (File entry : cpuEntries) {
            if (!entry.isDirectory() || !entry.getName().matches("cpu\\d+")) {
                continue;
            }
            File cpufreqDir = new File(entry, "cpufreq");

            Long currentKHz = readLongFromCandidates(
                    cpufreqDir,
                    new String[]{"scaling_cur_freq", "cpuinfo_cur_freq"});
            Long maxKHz = readLongFromCandidates(
                    cpufreqDir,
                    new String[]{"scaling_max_freq", "cpuinfo_max_freq"});

            if (currentKHz != null && currentKHz.longValue() > 0L) {
                currentHzSum += currentKHz.longValue() * 1000L;
                currentCount++;
            }
            if (maxKHz != null && maxKHz.longValue() > 0L) {
                maxHzSum += maxKHz.longValue() * 1000L;
                maxCount++;
            }
        }

        long averageCurrentHz = currentCount > 0 ? currentHzSum / currentCount : 0L;
        long averageMaxHz = maxCount > 0 ? maxHzSum / maxCount : 0L;
        return new long[]{averageCurrentHz, averageMaxHz};
    }

    private Long readLongFromCandidates(File directory, String[] fileNames) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        for (String fileName : fileNames) {
            String value = readFirstLine(new File(directory, fileName).getAbsolutePath());
            if (value == null) {
                continue;
            }
            Long parsed = parseLongOrNull(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private String readFirstLine(String filePath) {
        try (RandomAccessFile reader = new RandomAccessFile(filePath, "r")) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            String trimmed = line.trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (IOException exception) {
            return null;
        }
    }

    private long[] readMemoryMb() {
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return new long[]{0L, 0L};
        }
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long totalMb = memoryInfo.totalMem / 1024L / 1024L;
        long availableMb = memoryInfo.availMem / 1024L / 1024L;
        long usedMb = Math.max(0L, totalMb - availableMb);
        return new long[]{usedMb, totalMb};
    }

    private long[] readStorageMb() {
        StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        long blockSize = statFs.getBlockSizeLong();
        long totalMb = (statFs.getBlockCountLong() * blockSize) / 1024L / 1024L;
        long freeMb = (statFs.getAvailableBlocksLong() * blockSize) / 1024L / 1024L;
        return new long[]{freeMb, totalMb};
    }

    private long[] readNetworkBytesPerSecond() {
        long rxBytes = TrafficStats.getTotalRxBytes();
        long txBytes = TrafficStats.getTotalTxBytes();
        if (rxBytes == TrafficStats.UNSUPPORTED || txBytes == TrafficStats.UNSUPPORTED) {
            return new long[]{0L, 0L};
        }

        long rxDelta = lastRxBytes <= 0L ? 0L : Math.max(0L, rxBytes - lastRxBytes);
        long txDelta = lastTxBytes <= 0L ? 0L : Math.max(0L, txBytes - lastTxBytes);
        lastRxBytes = rxBytes;
        lastTxBytes = txBytes;

        if (intervalMs <= 0L) {
            return new long[]{rxDelta, txDelta};
        }
        double scale = 1000d / (double) intervalMs;
        return new long[]{
                Math.round(rxDelta * scale),
                Math.round(txDelta * scale)
        };
    }

    private long readNetworkMaxBytesPerSecond() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return 0L;
        }
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return 0L;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
        if (capabilities == null) {
            return 0L;
        }

        long downstreamBytesPerSec = kilobitsToBytesPerSecond(capabilities.getLinkDownstreamBandwidthKbps());
        long upstreamBytesPerSec = kilobitsToBytesPerSecond(capabilities.getLinkUpstreamBandwidthKbps());
        return downstreamBytesPerSec + upstreamBytesPerSec;
    }

    private long kilobitsToBytesPerSecond(int kilobitsPerSecond) {
        if (kilobitsPerSecond <= 0) {
            return 0L;
        }
        return (kilobitsPerSecond * 1000L) / 8L;
    }

    private Long parseLongOrNull(String rawValue) {
        try {
            return Long.valueOf(rawValue);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
