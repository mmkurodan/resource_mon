package com.micklab.resource_mon;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Debug;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProcessInspector {
    private static final long RECENT_APP_WINDOW_MS = 30L * 60L * 1000L;

    public static final class ProcessEntry {
        public final String label;
        public final String processName;
        public final int pid;
        public final int importance;
        public final String memoryText;
        public final String cpuText;
        public final long memorySortValue;
        public final double cpuSortValue;
        public final String packageSummary;

        ProcessEntry(
                String label,
                String processName,
                int pid,
                int importance,
                String memoryText,
                String cpuText,
                long memorySortValue,
                double cpuSortValue,
                String packageSummary) {
            this.label = label;
            this.processName = processName;
            this.pid = pid;
            this.importance = importance;
            this.memoryText = memoryText;
            this.cpuText = cpuText;
            this.memorySortValue = memorySortValue;
            this.cpuSortValue = cpuSortValue;
            this.packageSummary = packageSummary;
        }
    }

    public static final class RecentAppEntry {
        public final String label;
        public final String packageName;
        public final long lastTimeUsed;
        public final long totalForegroundTimeMs;

        RecentAppEntry(String label, String packageName, long lastTimeUsed, long totalForegroundTimeMs) {
            this.label = label;
            this.packageName = packageName;
            this.lastTimeUsed = lastTimeUsed;
            this.totalForegroundTimeMs = totalForegroundTimeMs;
        }
    }

    public static final class ProcessReport {
        public final String note;
        public final boolean usageAccessGranted;
        public final List<ProcessEntry> processEntries;
        public final List<RecentAppEntry> recentApps;

        ProcessReport(
                String note,
                boolean usageAccessGranted,
                List<ProcessEntry> processEntries,
                List<RecentAppEntry> recentApps) {
            this.note = note;
            this.usageAccessGranted = usageAccessGranted;
            this.processEntries = processEntries;
            this.recentApps = recentApps;
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

    private static final class ProcessCpuSample {
        final long totalTicks;

        ProcessCpuSample(long totalTicks) {
            this.totalTicks = totalTicks;
        }
    }

    private final Context context;
    private final ActivityManager activityManager;
    private final PackageManager packageManager;
    private final UsageStatsManager usageStatsManager;
    private final Map<Integer, ProcessCpuSample> previousProcessSamples = new HashMap<>();

    private CpuTotals previousCpuTotals;

    public ProcessInspector(Context context) {
        this.context = context.getApplicationContext();
        this.activityManager =
                (ActivityManager) this.context.getSystemService(Context.ACTIVITY_SERVICE);
        this.packageManager = this.context.getPackageManager();
        this.usageStatsManager =
                (UsageStatsManager) this.context.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    public ProcessReport collect() {
        List<ProcessEntry> entries = collectRunningProcesses();
        boolean usageAccessGranted = hasUsageAccess();
        List<RecentAppEntry> recentApps = usageAccessGranted ? collectRecentApps() : Collections.<RecentAppEntry>emptyList();

        StringBuilder noteBuilder = new StringBuilder();
        noteBuilder.append("Per-process CPU and memory are best-effort on Android. ")
                .append("Newer Android versions often hide other apps' live stats unless the device is rooted or the app has privileged access.");
        if (entries.isEmpty()) {
            noteBuilder.append("\nNo running processes were exposed to this app via ActivityManager.");
        } else if (entries.size() == 1) {
            noteBuilder.append("\nOnly one visible process was exposed; this is normal on modern Android builds.");
        }
        if (!usageAccessGranted) {
            noteBuilder.append("\nUsage access is off, so the recent-app view is limited.");
        }

        return new ProcessReport(noteBuilder.toString(), usageAccessGranted, entries, recentApps);
    }

    public Intent buildUsageAccessIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    private List<ProcessEntry> collectRunningProcesses() {
        if (activityManager == null) {
            return Collections.emptyList();
        }

        List<ActivityManager.RunningAppProcessInfo> runningProcesses = activityManager.getRunningAppProcesses();
        if (runningProcesses == null || runningProcesses.isEmpty()) {
            return Collections.emptyList();
        }

        CpuTotals totalCpu = readCpuTotals();
        List<ProcessEntry> entries = new ArrayList<>();
        Map<Integer, ProcessCpuSample> currentProcessSamples = new HashMap<>();

        for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
            if (processInfo == null) {
                continue;
            }
            ProcessCpuSample currentCpuSample = readProcessCpuSample(processInfo.pid);
            if (currentCpuSample != null) {
                currentProcessSamples.put(processInfo.pid, currentCpuSample);
            }
            Debug.MemoryInfo[] memoryInfoArray =
                    activityManager.getProcessMemoryInfo(new int[]{processInfo.pid});
            long memoryKb = 0L;
            String memoryText = "Unavailable";
            if (memoryInfoArray != null && memoryInfoArray.length > 0 && memoryInfoArray[0] != null) {
                int totalPss = memoryInfoArray[0].getTotalPss();
                if (totalPss > 0) {
                    memoryKb = totalPss;
                    memoryText = formatKilobytes(totalPss);
                }
            }

            double cpuPercent = Double.NaN;
            String cpuText = "Unavailable";
            if (totalCpu != null && previousCpuTotals != null && currentCpuSample != null) {
                ProcessCpuSample baseline = previousProcessSamples.get(processInfo.pid);
                if (baseline != null) {
                    long processDelta = currentCpuSample.totalTicks - baseline.totalTicks;
                    long totalDelta = totalCpu.total - previousCpuTotals.total;
                    if (processDelta >= 0L && totalDelta > 0L) {
                        cpuPercent = Math.max(0d, (processDelta * 100.0d) / totalDelta);
                        cpuText = String.format(Locale.US, "%.1f%%", cpuPercent);
                    } else {
                        cpuText = "Sampling...";
                    }
                } else {
                    cpuText = "Sampling...";
                }
            }

            String packageSummary = processInfo.pkgList == null || processInfo.pkgList.length == 0
                    ? processInfo.processName
                    : TextUtils.join(", ", processInfo.pkgList);
            entries.add(new ProcessEntry(
                    resolveLabel(processInfo),
                    processInfo.processName,
                    processInfo.pid,
                    processInfo.importance,
                    memoryText,
                    cpuText,
                    memoryKb,
                    Double.isNaN(cpuPercent) ? -1d : cpuPercent,
                    packageSummary));
        }

        previousCpuTotals = totalCpu;
        previousProcessSamples.clear();
        previousProcessSamples.putAll(currentProcessSamples);

        Collections.sort(entries, new Comparator<ProcessEntry>() {
            @Override
            public int compare(ProcessEntry left, ProcessEntry right) {
                int memoryCompare = Long.compare(right.memorySortValue, left.memorySortValue);
                if (memoryCompare != 0) {
                    return memoryCompare;
                }
                int cpuCompare = Double.compare(right.cpuSortValue, left.cpuSortValue);
                if (cpuCompare != 0) {
                    return cpuCompare;
                }
                int importanceCompare = Integer.compare(left.importance, right.importance);
                if (importanceCompare != 0) {
                    return importanceCompare;
                }
                return left.label.compareToIgnoreCase(right.label);
            }
        });
        return entries;
    }

    private List<RecentAppEntry> collectRecentApps() {
        if (usageStatsManager == null) {
            return Collections.emptyList();
        }
        long endTime = System.currentTimeMillis();
        long startTime = endTime - RECENT_APP_WINDOW_MS;
        List<UsageStats> usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime);
        if (usageStats == null || usageStats.isEmpty()) {
            return Collections.emptyList();
        }

        List<RecentAppEntry> recentApps = new ArrayList<>();
        for (UsageStats usageStat : usageStats) {
            if (usageStat == null || usageStat.getLastTimeUsed() <= 0L) {
                continue;
            }
            recentApps.add(new RecentAppEntry(
                    resolveLabel(usageStat.getPackageName(), usageStat.getPackageName()),
                    usageStat.getPackageName(),
                    usageStat.getLastTimeUsed(),
                    usageStat.getTotalTimeInForeground()));
        }

        Collections.sort(recentApps, new Comparator<RecentAppEntry>() {
            @Override
            public int compare(RecentAppEntry left, RecentAppEntry right) {
                return Long.compare(right.lastTimeUsed, left.lastTimeUsed);
            }
        });

        if (recentApps.size() > 10) {
            return new ArrayList<>(recentApps.subList(0, 10));
        }
        return recentApps;
    }

    private boolean hasUsageAccess() {
        if (usageStatsManager == null) {
            return false;
        }
        long endTime = System.currentTimeMillis();
        List<UsageStats> usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                endTime - 5L * 60L * 1000L,
                endTime);
        return usageStats != null && !usageStats.isEmpty();
    }

    private String resolveLabel(ActivityManager.RunningAppProcessInfo processInfo) {
        if (processInfo.pkgList != null) {
            for (String packageName : processInfo.pkgList) {
                String label = resolveLabel(packageName, null);
                if (!TextUtils.isEmpty(label) && !label.equals(packageName)) {
                    return label;
                }
            }
        }
        return resolveLabel(processInfo.processName, processInfo.processName);
    }

    private String resolveLabel(String packageName, String fallback) {
        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(applicationInfo);
            if (!TextUtils.isEmpty(label)) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Use the fallback value below.
        }
        return TextUtils.isEmpty(fallback) ? packageName : fallback;
    }

    private CpuTotals readCpuTotals() {
        String line = readFirstLine("/proc/stat");
        if (TextUtils.isEmpty(line)) {
            return null;
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5 || !"cpu".equals(parts[0])) {
            return null;
        }
        long total = 0L;
        for (int index = 1; index < parts.length; index++) {
            Long value = parseLong(parts[index]);
            if (value == null) {
                return null;
            }
            total += value.longValue();
        }
        Long idle = parseLong(parts[4]);
        if (idle == null) {
            return null;
        }
        if (parts.length > 5) {
            Long ioWait = parseLong(parts[5]);
            if (ioWait != null) {
                idle = Long.valueOf(idle.longValue() + ioWait.longValue());
            }
        }
        return new CpuTotals(total, idle.longValue());
    }

    private ProcessCpuSample readProcessCpuSample(int pid) {
        String line = readFirstLine("/proc/" + pid + "/stat");
        if (TextUtils.isEmpty(line)) {
            return null;
        }
        int nameEndIndex = line.lastIndexOf(')');
        if (nameEndIndex <= 0 || nameEndIndex + 2 >= line.length()) {
            return null;
        }
        String[] parts = line.substring(nameEndIndex + 2).trim().split("\\s+");
        if (parts.length <= 12) {
            return null;
        }
        Long userTicks = parseLong(parts[11]);
        Long systemTicks = parseLong(parts[12]);
        if (userTicks == null || systemTicks == null) {
            return null;
        }
        return new ProcessCpuSample(userTicks.longValue() + systemTicks.longValue());
    }

    private String readFirstLine(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        } catch (IOException ignored) {
            return null;
        }
    }

    private Long parseLong(String rawValue) {
        try {
            return Long.valueOf(rawValue);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatKilobytes(long kilobytes) {
        return android.text.format.Formatter.formatShortFileSize(context, kilobytes * 1024L);
    }
}
