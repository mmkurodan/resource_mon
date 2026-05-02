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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MetricsSampler {
    private static final String CPU_SYSFS_ROOT = "/sys/devices/system/cpu";

    public interface Listener {
        void onSample(MetricsSnapshot snapshot);
    }

    public static final class MetricsSnapshot {
        public final long timestampMillis;
        public final double cpuUsagePercent;
        public final double cpuAverageMhz;
        public final double cpuMaxMhz;
        public final long ramUsedMb;
        public final long ramTotalMb;
        public final MemoryDetails memoryDetails;
        public final long storageFreeMb;
        public final long storageTotalMb;
        public final long networkBytesPerSec;
        public final long networkMaxBytesPerSec;

        MetricsSnapshot(
                long timestampMillis,
                double cpuUsagePercent,
                double cpuAverageMhz,
                double cpuMaxMhz,
                long ramUsedMb,
                long ramTotalMb,
                MemoryDetails memoryDetails,
                long storageFreeMb,
                long storageTotalMb,
                long networkBytesPerSec,
                long networkMaxBytesPerSec) {
            this.timestampMillis = timestampMillis;
            this.cpuUsagePercent = cpuUsagePercent;
            this.cpuAverageMhz = cpuAverageMhz;
            this.cpuMaxMhz = cpuMaxMhz;
            this.ramUsedMb = ramUsedMb;
            this.ramTotalMb = ramTotalMb;
            this.memoryDetails = memoryDetails;
            this.storageFreeMb = storageFreeMb;
            this.storageTotalMb = storageTotalMb;
            this.networkBytesPerSec = networkBytesPerSec;
            this.networkMaxBytesPerSec = networkMaxBytesPerSec;
        }

        public double ramUsagePercent() {
            if (ramTotalMb <= 0L) {
                return 0d;
            }
            return Math.max(0d, Math.min(100d, (ramUsedMb * 100.0d) / ramTotalMb));
        }
    }

    public static final class MemoryDetails {
        public final long totalBytes;
        public final long usedBytes;
        public final long availableBytes;
        public final long freeBytes;
        public final long buffersBytes;
        public final long cachedBytes;
        public final long slabBytes;
        public final long reclaimableSlabBytes;
        public final long unreclaimableSlabBytes;
        public final long activeBytes;
        public final long inactiveBytes;
        public final long shmemBytes;
        public final long swapTotalBytes;
        public final long swapFreeBytes;
        public final long swapCachedBytes;
        public final long committedBytes;
        public final long commitLimitBytes;
        public final long vmallocUsedBytes;
        public final long vmallocTotalBytes;

        MemoryDetails(
                long totalBytes,
                long usedBytes,
                long availableBytes,
                long freeBytes,
                long buffersBytes,
                long cachedBytes,
                long slabBytes,
                long reclaimableSlabBytes,
                long unreclaimableSlabBytes,
                long activeBytes,
                long inactiveBytes,
                long shmemBytes,
                long swapTotalBytes,
                long swapFreeBytes,
                long swapCachedBytes,
                long committedBytes,
                long commitLimitBytes,
                long vmallocUsedBytes,
                long vmallocTotalBytes) {
            this.totalBytes = totalBytes;
            this.usedBytes = usedBytes;
            this.availableBytes = availableBytes;
            this.freeBytes = freeBytes;
            this.buffersBytes = buffersBytes;
            this.cachedBytes = cachedBytes;
            this.slabBytes = slabBytes;
            this.reclaimableSlabBytes = reclaimableSlabBytes;
            this.unreclaimableSlabBytes = unreclaimableSlabBytes;
            this.activeBytes = activeBytes;
            this.inactiveBytes = inactiveBytes;
            this.shmemBytes = shmemBytes;
            this.swapTotalBytes = swapTotalBytes;
            this.swapFreeBytes = swapFreeBytes;
            this.swapCachedBytes = swapCachedBytes;
            this.committedBytes = committedBytes;
            this.commitLimitBytes = commitLimitBytes;
            this.vmallocUsedBytes = vmallocUsedBytes;
            this.vmallocTotalBytes = vmallocTotalBytes;
        }

        public long swapUsedBytes() {
            return Math.max(0L, swapTotalBytes - swapFreeBytes);
        }

        public long cacheLikeBytes() {
            return Math.max(0L, buffersBytes + cachedBytes + reclaimableSlabBytes);
        }

        public double committedPercent() {
            if (commitLimitBytes <= 0L) {
                return 0d;
            }
            return Math.max(0d, Math.min(100d, (committedBytes * 100.0d) / commitLimitBytes));
        }
    }

    private static final class CpuTotals {
        final long total;
        final long idle;

        CpuTotals(long total, long idle) {
            this.total = total;
            this.idle = idle;
        }
    }

    private final Context context;
    private final long intervalMs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, CpuTotals> previousCpuCoreTotals = new HashMap<>();

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
        double[] cpuFrequency = readCpuAverageFrequencyMhz();
        double cpuUsagePercent = readCpuUsagePercent(cpuFrequency[0], cpuFrequency[1]);
        MemoryDetails memoryDetails = readMemoryDetails();
        long[] storage = readStorageMb();
        long[] network = readNetworkBytesPerSecond();
        long networkBytesPerSec = Math.max(0L, network[0]) + Math.max(0L, network[1]);
        long networkMaxBytesPerSec = readNetworkMaxBytesPerSecond();
        return new MetricsSnapshot(
                System.currentTimeMillis(),
                cpuUsagePercent,
                cpuFrequency[0],
                cpuFrequency[1],
                memoryDetails.usedBytes / 1024L / 1024L,
                memoryDetails.totalBytes / 1024L / 1024L,
                memoryDetails,
                storage[0],
                storage[1],
                networkBytesPerSec,
                networkMaxBytesPerSec);
    }

    private double[] readCpuAverageFrequencyMhz() {
        File cpuRoot = new File(CPU_SYSFS_ROOT);
        File[] cpuEntries = cpuRoot.listFiles();
        if (cpuEntries == null || cpuEntries.length == 0) {
            return new double[]{0d, 0d};
        }

        double currentMhzSum = 0d;
        double maxMhzSum = 0d;
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
                    new String[]{"cpuinfo_max_freq", "scaling_max_freq"});

            if (currentKHz != null && currentKHz.longValue() > 0L) {
                currentMhzSum += currentKHz.longValue() / 1000.0d;
                currentCount++;
            }
            if (maxKHz != null && maxKHz.longValue() > 0L) {
                maxMhzSum += maxKHz.longValue() / 1000.0d;
                maxCount++;
            }
        }

        double averageCurrentMhz = currentCount > 0 ? currentMhzSum / currentCount : 0d;
        double averageMaxMhz = maxCount > 0 ? maxMhzSum / maxCount : 0d;
        return new double[]{averageCurrentMhz, averageMaxMhz};
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

    private double readCpuUsagePercent(double cpuAverageMhz, double cpuMaxMhz) {
        double coreUsagePercent = readCpuCoreUsagePercent();
        if (!Double.isNaN(coreUsagePercent) && coreUsagePercent > 0d) {
            return coreUsagePercent;
        }

        double frequencyPercent = readCpuFrequencyPercent(cpuAverageMhz, cpuMaxMhz);
        if (frequencyPercent > 0d) {
            return frequencyPercent;
        }
        return Double.isNaN(coreUsagePercent) ? 0d : coreUsagePercent;
    }

    private double readCpuCoreUsagePercent() {
        Map<String, CpuTotals> currentCpuCoreTotals = new HashMap<>();
        double usagePercentSum = 0d;
        int sampledCoreCount = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("cpu")) {
                    break;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 5 || !parts[0].matches("cpu\\d+")) {
                    continue;
                }
                CpuTotals currentTotals = parseCpuTotals(parts);
                if (currentTotals == null) {
                    continue;
                }
                currentCpuCoreTotals.put(parts[0], currentTotals);

                CpuTotals baseline = previousCpuCoreTotals.get(parts[0]);
                if (baseline == null) {
                    continue;
                }
                long totalDelta = currentTotals.total - baseline.total;
                long idleDelta = currentTotals.idle - baseline.idle;
                if (totalDelta <= 0L || idleDelta < 0L) {
                    continue;
                }
                usagePercentSum += ((totalDelta - idleDelta) * 100.0d) / totalDelta;
                sampledCoreCount++;
            }
        } catch (IOException ignored) {
            previousCpuCoreTotals.clear();
            return Double.NaN;
        }

        previousCpuCoreTotals.clear();
        previousCpuCoreTotals.putAll(currentCpuCoreTotals);
        if (sampledCoreCount <= 0) {
            return Double.NaN;
        }
        return Math.max(0d, Math.min(100d, usagePercentSum / sampledCoreCount));
    }

    private double readCpuFrequencyPercent(double cpuAverageMhz, double cpuMaxMhz) {
        if (cpuAverageMhz <= 0d || cpuMaxMhz <= 0d) {
            return 0d;
        }
        return Math.max(0d, Math.min(100d, (cpuAverageMhz * 100.0d) / cpuMaxMhz));
    }

    private CpuTotals parseCpuTotals(String[] parts) {
        long total = 0L;
        for (int index = 1; index < parts.length; index++) {
            Long value = parseLongOrNull(parts[index]);
            if (value == null) {
                return null;
            }
            total += value.longValue();
        }

        Long idle = parseLongOrNull(parts[4]);
        if (idle == null) {
            return null;
        }
        if (parts.length > 5) {
            Long ioWait = parseLongOrNull(parts[5]);
            if (ioWait != null) {
                idle = Long.valueOf(idle.longValue() + ioWait.longValue());
            }
        }
        return new CpuTotals(total, idle.longValue());
    }

    private MemoryDetails readMemoryDetails() {
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }

        long totalBytes = memoryInfo.totalMem;
        long availableBytes = memoryInfo.availMem;
        long freeBytes = 0L;
        long buffersBytes = 0L;
        long cachedBytes = 0L;
        long slabBytes = 0L;
        long reclaimableSlabBytes = 0L;
        long unreclaimableSlabBytes = 0L;
        long activeBytes = 0L;
        long inactiveBytes = 0L;
        long shmemBytes = 0L;
        long swapTotalBytes = 0L;
        long swapFreeBytes = 0L;
        long swapCachedBytes = 0L;
        long committedBytes = 0L;
        long commitLimitBytes = 0L;
        long vmallocUsedBytes = 0L;
        long vmallocTotalBytes = 0L;

        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int colonIndex = line.indexOf(':');
                if (colonIndex <= 0) {
                    continue;
                }

                String key = line.substring(0, colonIndex).trim();
                String rawValue = line.substring(colonIndex + 1).trim();
                String[] parts = rawValue.split("\\s+");
                if (parts.length == 0) {
                    continue;
                }
                Long numericValue = parseLongOrNull(parts[0]);
                if (numericValue == null) {
                    continue;
                }
                long valueBytes = numericValue.longValue() * 1024L;

                if ("MemTotal".equals(key)) {
                    totalBytes = valueBytes;
                } else if ("MemAvailable".equals(key)) {
                    availableBytes = valueBytes;
                } else if ("MemFree".equals(key)) {
                    freeBytes = valueBytes;
                } else if ("Buffers".equals(key)) {
                    buffersBytes = valueBytes;
                } else if ("Cached".equals(key)) {
                    cachedBytes = valueBytes;
                } else if ("Slab".equals(key)) {
                    slabBytes = valueBytes;
                } else if ("SReclaimable".equals(key)) {
                    reclaimableSlabBytes = valueBytes;
                } else if ("SUnreclaim".equals(key)) {
                    unreclaimableSlabBytes = valueBytes;
                } else if ("Active".equals(key)) {
                    activeBytes = valueBytes;
                } else if ("Inactive".equals(key)) {
                    inactiveBytes = valueBytes;
                } else if ("Shmem".equals(key)) {
                    shmemBytes = valueBytes;
                } else if ("SwapTotal".equals(key)) {
                    swapTotalBytes = valueBytes;
                } else if ("SwapFree".equals(key)) {
                    swapFreeBytes = valueBytes;
                } else if ("SwapCached".equals(key)) {
                    swapCachedBytes = valueBytes;
                } else if ("Committed_AS".equals(key)) {
                    committedBytes = valueBytes;
                } else if ("CommitLimit".equals(key)) {
                    commitLimitBytes = valueBytes;
                } else if ("VmallocUsed".equals(key)) {
                    vmallocUsedBytes = valueBytes;
                } else if ("VmallocTotal".equals(key)) {
                    vmallocTotalBytes = valueBytes;
                }
            }
        } catch (IOException ignored) {
            // Fall back to ActivityManager totals when /proc/meminfo is unavailable.
        }

        if (slabBytes <= 0L && (reclaimableSlabBytes > 0L || unreclaimableSlabBytes > 0L)) {
            slabBytes = reclaimableSlabBytes + unreclaimableSlabBytes;
        }
        if (totalBytes <= 0L) {
            totalBytes = 0L;
        }
        if (availableBytes < 0L) {
            availableBytes = 0L;
        }
        long usedBytes = totalBytes > 0L
                ? Math.max(0L, totalBytes - availableBytes)
                : Math.max(0L, totalBytes - freeBytes);
        return new MemoryDetails(
                totalBytes,
                usedBytes,
                availableBytes,
                freeBytes,
                buffersBytes,
                cachedBytes,
                slabBytes,
                reclaimableSlabBytes,
                unreclaimableSlabBytes,
                activeBytes,
                inactiveBytes,
                shmemBytes,
                swapTotalBytes,
                swapFreeBytes,
                swapCachedBytes,
                committedBytes,
                commitLimitBytes,
                vmallocUsedBytes,
                vmallocTotalBytes);
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
