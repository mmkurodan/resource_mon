package com.micklab.rm;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Debug;
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
    public static final class ProcessEntry {
        public final String label;
        public final String processName;
        public final int pid;
        public final int importance;
        public final boolean foregroundLike;
        public final String stateText;
        public final String memoryText;
        public final String cpuText;
        public final String addressSpaceText;
        public final long memorySortValue;
        public final double cpuSortValue;
        public final String packageSummary;

        ProcessEntry(
                String label,
                String processName,
                int pid,
                int importance,
                boolean foregroundLike,
                String stateText,
                String memoryText,
                String cpuText,
                String addressSpaceText,
                long memorySortValue,
                double cpuSortValue,
                String packageSummary) {
            this.label = label;
            this.processName = processName;
            this.pid = pid;
            this.importance = importance;
            this.foregroundLike = foregroundLike;
            this.stateText = stateText;
            this.memoryText = memoryText;
            this.cpuText = cpuText;
            this.addressSpaceText = addressSpaceText;
            this.memorySortValue = memorySortValue;
            this.cpuSortValue = cpuSortValue;
            this.packageSummary = packageSummary;
        }

        public boolean hasCpuSample() {
            return cpuSortValue >= 0d;
        }

        public boolean hasMemorySample() {
            return memorySortValue > 0L;
        }
    }

    public static final class ProcessReport {
        public final String note;
        public final List<ProcessEntry> processEntries;

        ProcessReport(
                String note,
                List<ProcessEntry> processEntries) {
            this.note = note;
            this.processEntries = processEntries;
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

    private static final class ProcessAddressSpaceSample {
        final long currentKb;
        final long peakKb;

        ProcessAddressSpaceSample(long currentKb, long peakKb) {
            this.currentKb = currentKb;
            this.peakKb = peakKb;
        }
    }

    private final Context context;
    private final ActivityManager activityManager;
    private final PackageManager packageManager;
    private final Map<Integer, ProcessCpuSample> previousProcessSamples = new HashMap<>();

    private CpuTotals previousCpuTotals;

    public ProcessInspector(Context context) {
        this.context = context.getApplicationContext();
        this.activityManager =
                (ActivityManager) this.context.getSystemService(Context.ACTIVITY_SERVICE);
        this.packageManager = this.context.getPackageManager();
    }

    public ProcessReport collect() {
        List<ProcessEntry> entries = collectRunningProcesses();
        StringBuilder noteBuilder = new StringBuilder();
        noteBuilder.append("Current process metrics include foreground and background processes that Android exposed to this app. ")
                .append("Foreground history averages are recorded while monitoring is on and the process importance is FOREGROUND or VISIBLE.");
        noteBuilder.append("\nPer-process CPU and memory remain best-effort on Android; newer versions often hide other apps' live stats unless the device is rooted or the app has privileged access.");
        if (entries.isEmpty()) {
            noteBuilder.append("\nNo running processes were exposed to this app via ActivityManager.");
        } else if (entries.size() == 1) {
            noteBuilder.append("\nOnly one visible process was exposed; this is normal on modern Android builds.");
        }
        return new ProcessReport(noteBuilder.toString(), entries);
    }

    public List<ProcessEntry> collectRunningProcessesSnapshot() {
        return collectRunningProcesses();
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

            ProcessAddressSpaceSample addressSpaceSample = readProcessAddressSpace(processInfo.pid);
            String addressSpaceText = formatAddressSpace(addressSpaceSample);
            boolean foregroundLike = isForegroundLike(processInfo.importance);
            String stateText = describeImportance(processInfo.importance);

            String packageSummary = processInfo.pkgList == null || processInfo.pkgList.length == 0
                    ? processInfo.processName
                    : TextUtils.join(", ", processInfo.pkgList);
            entries.add(new ProcessEntry(
                    resolveLabel(processInfo),
                    processInfo.processName,
                    processInfo.pid,
                    processInfo.importance,
                    foregroundLike,
                    stateText,
                    memoryText,
                    cpuText,
                    addressSpaceText,
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

    private ProcessAddressSpaceSample readProcessAddressSpace(int pid) {
        long currentKb = 0L;
        long peakKb = 0L;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/status"))) {
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
                Long valueKb = parseLong(parts[0]);
                if (valueKb == null) {
                    continue;
                }
                if ("VmSize".equals(key)) {
                    currentKb = valueKb.longValue();
                } else if ("VmPeak".equals(key)) {
                    peakKb = valueKb.longValue();
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        if (currentKb <= 0L && peakKb <= 0L) {
            return null;
        }
        return new ProcessAddressSpaceSample(currentKb, peakKb);
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

    private String formatAddressSpace(ProcessAddressSpaceSample sample) {
        if (sample == null) {
            return "Unavailable";
        }
        if (sample.currentKb > 0L && sample.peakKb > 0L) {
            return formatKilobytes(sample.currentKb) + " / peak " + formatKilobytes(sample.peakKb);
        }
        if (sample.currentKb > 0L) {
            return formatKilobytes(sample.currentKb);
        }
        return "Peak " + formatKilobytes(sample.peakKb);
    }

    private boolean isForegroundLike(int importance) {
        return importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }

    private String describeImportance(int importance) {
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return "Foreground";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
            return "Foreground service";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE) {
            return "Perceptible";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
            return "Visible";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) {
            return "Service";
        }
        if (importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) {
            return "Cached";
        }
        return "Background";
    }
}
