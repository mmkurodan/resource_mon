package com.micklab.rm;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Debug;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProcessInspector {
    private static final int IMPORTANCE_UNKNOWN = Integer.MAX_VALUE;

    public static final class ProcessEntry {
        public final String label;
        public final String processName;
        public final int pid;
        public final int uid;
        public final int importance;
        public final boolean foregroundLike;
        public final String stateText;
        public final String linuxStateText;
        public final String memoryText;
        public final String cpuText;
        public final String addressSpaceText;
        public final long memorySortValue;
        public final double cpuSortValue;
        public final long addressSpaceCurrentKb;
        public final long addressSpacePeakKb;
        public final long swapKb;
        public final int threadCount;
        public final String packageSummary;

        ProcessEntry(
                String label,
                String processName,
                int pid,
                int uid,
                int importance,
                boolean foregroundLike,
                String stateText,
                String linuxStateText,
                String memoryText,
                String cpuText,
                String addressSpaceText,
                long memorySortValue,
                double cpuSortValue,
                long addressSpaceCurrentKb,
                long addressSpacePeakKb,
                long swapKb,
                int threadCount,
                String packageSummary) {
            this.label = label;
            this.processName = processName;
            this.pid = pid;
            this.uid = uid;
            this.importance = importance;
            this.foregroundLike = foregroundLike;
            this.stateText = stateText;
            this.linuxStateText = linuxStateText;
            this.memoryText = memoryText;
            this.cpuText = cpuText;
            this.addressSpaceText = addressSpaceText;
            this.memorySortValue = memorySortValue;
            this.cpuSortValue = cpuSortValue;
            this.addressSpaceCurrentKb = addressSpaceCurrentKb;
            this.addressSpacePeakKb = addressSpacePeakKb;
            this.swapKb = swapKb;
            this.threadCount = threadCount;
            this.packageSummary = packageSummary;
        }

        public boolean hasCpuSample() {
            return cpuSortValue >= 0d;
        }

        public boolean hasMemorySample() {
            return memorySortValue > 0L;
        }

        public boolean hasImportance() {
            return importance != IMPORTANCE_UNKNOWN;
        }
    }

    public static final class AppEntry {
        public final String label;
        public final String packageSummary;
        public final int uid;
        public final int processCount;
        public final int importance;
        public final boolean foregroundLike;
        public final String stateText;
        public final String memoryText;
        public final String cpuText;
        public final String addressSpaceText;
        public final long memorySortValue;
        public final double cpuSortValue;

        AppEntry(
                String label,
                String packageSummary,
                int uid,
                int processCount,
                int importance,
                boolean foregroundLike,
                String stateText,
                String memoryText,
                String cpuText,
                String addressSpaceText,
                long memorySortValue,
                double cpuSortValue) {
            this.label = label;
            this.packageSummary = packageSummary;
            this.uid = uid;
            this.processCount = processCount;
            this.importance = importance;
            this.foregroundLike = foregroundLike;
            this.stateText = stateText;
            this.memoryText = memoryText;
            this.cpuText = cpuText;
            this.addressSpaceText = addressSpaceText;
            this.memorySortValue = memorySortValue;
            this.cpuSortValue = cpuSortValue;
        }

        public boolean hasImportance() {
            return importance != IMPORTANCE_UNKNOWN;
        }
    }

    public static final class ProcessReport {
        public final String note;
        public final List<AppEntry> appEntries;
        public final List<ProcessEntry> processEntries;

        ProcessReport(
                String note,
                List<AppEntry> appEntries,
                List<ProcessEntry> processEntries) {
            this.note = note;
            this.appEntries = appEntries;
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
        final String statName;

        ProcessCpuSample(long totalTicks, String statName) {
            this.totalTicks = totalTicks;
            this.statName = statName;
        }
    }

    private static final class ProcStatusSample {
        final String name;
        final String stateText;
        final int uid;
        final long rssKb;
        final long currentVmKb;
        final long peakVmKb;
        final long swapKb;
        final int threadCount;

        ProcStatusSample(
                String name,
                String stateText,
                int uid,
                long rssKb,
                long currentVmKb,
                long peakVmKb,
                long swapKb,
                int threadCount) {
            this.name = name;
            this.stateText = stateText;
            this.uid = uid;
            this.rssKb = rssKb;
            this.currentVmKb = currentVmKb;
            this.peakVmKb = peakVmKb;
            this.swapKb = swapKb;
            this.threadCount = threadCount;
        }
    }

    private static final class VisibleProcessSample {
        final int pid;
        final int uid;
        final int importance;
        final boolean foregroundLike;
        final String processName;
        final String[] packageNames;
        final ProcessCpuSample cpuSample;
        final ProcStatusSample statusSample;

        VisibleProcessSample(
                int pid,
                int uid,
                int importance,
                boolean foregroundLike,
                String processName,
                String[] packageNames,
                ProcessCpuSample cpuSample,
                ProcStatusSample statusSample) {
            this.pid = pid;
            this.uid = uid;
            this.importance = importance;
            this.foregroundLike = foregroundLike;
            this.processName = processName;
            this.packageNames = packageNames;
            this.cpuSample = cpuSample;
            this.statusSample = statusSample;
        }
    }

    private static final class AppAggregate {
        final String label;
        final String packageSummary;
        final int uid;

        int processCount;
        int bestImportance = IMPORTANCE_UNKNOWN;
        boolean foregroundLike;
        long memoryKb;
        int memorySampleCount;
        double cpuPercentTotal;
        int cpuSampleCount;
        long addressCurrentKb;
        long addressPeakKb;
        long swapKb;
        int threadCount;

        AppAggregate(String label, String packageSummary, int uid) {
            this.label = label;
            this.packageSummary = packageSummary;
            this.uid = uid;
        }

        void merge(ProcessEntry processEntry) {
            processCount += 1;
            if (processEntry.hasImportance()) {
                bestImportance = Math.min(bestImportance, processEntry.importance);
            }
            foregroundLike = foregroundLike || processEntry.foregroundLike;
            if (processEntry.hasMemorySample()) {
                memoryKb += processEntry.memorySortValue;
                memorySampleCount += 1;
            }
            if (processEntry.hasCpuSample()) {
                cpuPercentTotal += processEntry.cpuSortValue;
                cpuSampleCount += 1;
            }
            addressCurrentKb += Math.max(0L, processEntry.addressSpaceCurrentKb);
            addressPeakKb += Math.max(0L, processEntry.addressSpacePeakKb);
            swapKb += Math.max(0L, processEntry.swapKb);
            threadCount += Math.max(0, processEntry.threadCount);
        }
    }

    private final Context context;
    private final ActivityManager activityManager;
    private final PackageManager packageManager;
    private final Map<Integer, ProcessCpuSample> previousProcessSamples = new HashMap<>();
    private final Map<String, String> labelCache = new HashMap<>();
    private final Map<String, Boolean> packageExistsCache = new HashMap<>();
    private final Map<Integer, String[]> uidPackagesCache = new HashMap<>();

    private CpuTotals previousCpuTotals;

    public ProcessInspector(Context context) {
        this.context = context.getApplicationContext();
        this.activityManager =
                (ActivityManager) this.context.getSystemService(Context.ACTIVITY_SERVICE);
        this.packageManager = this.context.getPackageManager();
    }

    public ProcessReport collect() {
        List<ProcessEntry> processEntries = collectRunningProcesses();
        List<AppEntry> appEntries = collectRunningApps(processEntries);
        int importanceCount = 0;
        for (ProcessEntry processEntry : processEntries) {
            if (processEntry.hasImportance()) {
                importanceCount += 1;
            }
        }
        return new ProcessReport(
                buildAccessNote(processEntries, appEntries, importanceCount),
                appEntries,
                processEntries);
    }

    public List<ProcessEntry> collectRunningProcessesSnapshot() {
        return collectRunningProcesses();
    }

    private String buildAccessNote(
            List<ProcessEntry> processEntries,
            List<AppEntry> appEntries,
            int importanceCount) {
        StringBuilder noteBuilder = new StringBuilder();
        noteBuilder.append("Enumerated ")
                .append(processEntries.size())
                .append(" visible PID(s) from /proc and grouped them into ")
                .append(appEntries.size())
                .append(" running app entry(ies).");
        if (importanceCount > 0) {
            noteBuilder.append("\nActivityManager still exposed Android importance for ")
                    .append(importanceCount)
                    .append(" process(es), so foreground/service state is shown when available.");
        } else {
            noteBuilder.append("\nAndroid importance was unavailable for every visible PID, so state falls back to procfs/Linux metadata.");
        }
        noteBuilder.append("\nCPU deltas, PSS, and cross-app memory remain best-effort on Android; newer releases may hide some live stats unless the device is rooted or the app has privileged access.");
        if (processEntries.isEmpty()) {
            noteBuilder.append("\nNo readable numeric /proc entries were visible to this app.");
        } else if (processEntries.size() == 1) {
            noteBuilder.append("\nOnly one readable process was visible; this often means the device is enforcing tight procfs isolation.");
        }
        return noteBuilder.toString();
    }

    private List<ProcessEntry> collectRunningProcesses() {
        Map<Integer, ActivityManager.RunningAppProcessInfo> runningProcessesByPid = getRunningProcessesByPid();
        List<VisibleProcessSample> visibleProcesses = enumerateVisibleProcesses(runningProcessesByPid);
        if (visibleProcesses.isEmpty()) {
            previousCpuTotals = readCpuTotals();
            previousProcessSamples.clear();
            return Collections.emptyList();
        }

        CpuTotals totalCpu = readCpuTotals();
        Map<Integer, Long> pssByPid = readPssKilobytes(visibleProcesses);
        List<ProcessEntry> entries = new ArrayList<>();
        Map<Integer, ProcessCpuSample> currentProcessSamples = new HashMap<>();

        for (VisibleProcessSample processSample : visibleProcesses) {
            if (processSample.cpuSample != null) {
                currentProcessSamples.put(processSample.pid, processSample.cpuSample);
            }

            long pssKb = pssByPid.containsKey(processSample.pid)
                    ? Math.max(0L, pssByPid.get(processSample.pid).longValue())
                    : 0L;
            long rssKb = processSample.statusSample == null ? 0L : processSample.statusSample.rssKb;
            long memoryKb = pssKb > 0L ? pssKb : rssKb;
            String memoryText = formatMemoryText(pssKb, rssKb);

            double cpuPercent = Double.NaN;
            String cpuText = "Unavailable";
            if (totalCpu != null && previousCpuTotals != null && processSample.cpuSample != null) {
                ProcessCpuSample baseline = previousProcessSamples.get(processSample.pid);
                if (baseline != null) {
                    long processDelta = processSample.cpuSample.totalTicks - baseline.totalTicks;
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

            long currentVmKb = processSample.statusSample == null ? 0L : processSample.statusSample.currentVmKb;
            long peakVmKb = processSample.statusSample == null ? 0L : processSample.statusSample.peakVmKb;
            long swapKb = processSample.statusSample == null ? 0L : processSample.statusSample.swapKb;
            int threadCount = processSample.statusSample == null ? 0 : processSample.statusSample.threadCount;
            String linuxStateText = processSample.statusSample == null
                    ? "Unavailable"
                    : nonEmptyOrFallback(processSample.statusSample.stateText, "Unavailable");
            String packageSummary = processSample.packageNames.length == 0
                    ? processSample.processName
                    : TextUtils.join(", ", processSample.packageNames);

            entries.add(new ProcessEntry(
                    resolveLabel(processSample.packageNames, processSample.processName, processSample.uid),
                    processSample.processName,
                    processSample.pid,
                    processSample.uid,
                    processSample.importance,
                    processSample.foregroundLike,
                    describeProcessState(processSample.importance, processSample.statusSample),
                    linuxStateText,
                    memoryText,
                    cpuText,
                    formatAddressSpace(currentVmKb, peakVmKb, swapKb, threadCount),
                    memoryKb,
                    Double.isNaN(cpuPercent) ? -1d : cpuPercent,
                    currentVmKb,
                    peakVmKb,
                    swapKb,
                    threadCount,
                    packageSummary));
        }

        previousCpuTotals = totalCpu;
        previousProcessSamples.clear();
        previousProcessSamples.putAll(currentProcessSamples);
        sortProcessEntries(entries);
        return entries;
    }

    private List<AppEntry> collectRunningApps(List<ProcessEntry> processEntries) {
        if (processEntries.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, AppAggregate> appsByKey = new HashMap<>();
        for (ProcessEntry processEntry : processEntries) {
            String appKey = processEntry.uid + "|" + processEntry.packageSummary;
            AppAggregate aggregate = appsByKey.get(appKey);
            if (aggregate == null) {
                aggregate = new AppAggregate(
                        processEntry.label,
                        processEntry.packageSummary,
                        processEntry.uid);
                appsByKey.put(appKey, aggregate);
            }
            aggregate.merge(processEntry);
        }

        List<AppEntry> appEntries = new ArrayList<>();
        for (AppAggregate aggregate : appsByKey.values()) {
            double cpuPercent = aggregate.cpuSampleCount > 0
                    ? aggregate.cpuPercentTotal
                    : Double.NaN;
            appEntries.add(new AppEntry(
                    aggregate.label,
                    aggregate.packageSummary,
                    aggregate.uid,
                    aggregate.processCount,
                    aggregate.bestImportance,
                    aggregate.foregroundLike,
                    aggregate.bestImportance == IMPORTANCE_UNKNOWN
                            ? "Importance unavailable"
                            : describeImportance(aggregate.bestImportance),
                    aggregate.memorySampleCount > 0
                            ? formatKilobytes(aggregate.memoryKb)
                            : "Unavailable",
                    Double.isNaN(cpuPercent)
                            ? "Unavailable"
                            : String.format(Locale.US, "%.1f%%", cpuPercent),
                    formatAddressSpace(
                            aggregate.addressCurrentKb,
                            aggregate.addressPeakKb,
                            aggregate.swapKb,
                            aggregate.threadCount),
                    aggregate.memoryKb,
                    Double.isNaN(cpuPercent) ? -1d : cpuPercent));
        }
        sortAppEntries(appEntries);
        return appEntries;
    }

    private Map<Integer, ActivityManager.RunningAppProcessInfo> getRunningProcessesByPid() {
        if (activityManager == null) {
            return Collections.emptyMap();
        }
        List<ActivityManager.RunningAppProcessInfo> runningProcesses = activityManager.getRunningAppProcesses();
        if (runningProcesses == null || runningProcesses.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Integer, ActivityManager.RunningAppProcessInfo> byPid = new HashMap<>();
        for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
            if (processInfo != null) {
                byPid.put(processInfo.pid, processInfo);
            }
        }
        return byPid;
    }

    private List<VisibleProcessSample> enumerateVisibleProcesses(
            Map<Integer, ActivityManager.RunningAppProcessInfo> runningProcessesByPid) {
        File[] procEntries = new File("/proc").listFiles();
        if (procEntries == null || procEntries.length == 0) {
            return Collections.emptyList();
        }

        List<VisibleProcessSample> visibleProcesses = new ArrayList<>();
        for (File procEntry : procEntries) {
            if (procEntry == null || !procEntry.isDirectory()) {
                continue;
            }
            Integer pid = tryParsePositiveInt(procEntry.getName());
            if (pid == null) {
                continue;
            }

            ProcStatusSample statusSample = readProcessStatus(pid.intValue());
            ProcessCpuSample cpuSample = readProcessCpuSample(pid.intValue());
            String processName = readProcessName(pid.intValue(), statusSample, cpuSample);
            if (TextUtils.isEmpty(processName)) {
                continue;
            }

            ActivityManager.RunningAppProcessInfo processInfo = runningProcessesByPid.get(pid);
            int uid = statusSample == null ? -1 : statusSample.uid;
            int importance = processInfo == null ? IMPORTANCE_UNKNOWN : processInfo.importance;
            String[] packageNames = resolvePackageNames(processInfo, uid, processName);
            visibleProcesses.add(new VisibleProcessSample(
                    pid.intValue(),
                    uid,
                    importance,
                    processInfo != null && isForegroundLike(processInfo.importance),
                    processName,
                    packageNames,
                    cpuSample,
                    statusSample));
        }
        return visibleProcesses;
    }

    private Map<Integer, Long> readPssKilobytes(List<VisibleProcessSample> visibleProcesses) {
        if (activityManager == null || visibleProcesses.isEmpty()) {
            return Collections.emptyMap();
        }

        int[] pids = new int[visibleProcesses.size()];
        for (int index = 0; index < visibleProcesses.size(); index++) {
            pids[index] = visibleProcesses.get(index).pid;
        }

        Map<Integer, Long> pssByPid = new HashMap<>();
        try {
            Debug.MemoryInfo[] memoryInfos = activityManager.getProcessMemoryInfo(pids);
            if (memoryInfos == null) {
                return pssByPid;
            }
            int limit = Math.min(memoryInfos.length, pids.length);
            for (int index = 0; index < limit; index++) {
                Debug.MemoryInfo memoryInfo = memoryInfos[index];
                if (memoryInfo == null) {
                    continue;
                }
                int totalPss = memoryInfo.getTotalPss();
                if (totalPss > 0) {
                    pssByPid.put(Integer.valueOf(pids[index]), Long.valueOf(totalPss));
                }
            }
        } catch (RuntimeException ignored) {
            return pssByPid;
        }
        return pssByPid;
    }

    private String[] resolvePackageNames(
            ActivityManager.RunningAppProcessInfo processInfo,
            int uid,
            String processName) {
        LinkedHashSet<String> packageNames = new LinkedHashSet<>();
        if (processInfo != null && processInfo.pkgList != null) {
            for (String packageName : processInfo.pkgList) {
                if (!TextUtils.isEmpty(packageName)) {
                    packageNames.add(packageName);
                }
            }
        }

        if (uid >= 0) {
            String[] packagesForUid = getPackagesForUid(uid);
            for (String packageName : packagesForUid) {
                if (!TextUtils.isEmpty(packageName)) {
                    packageNames.add(packageName);
                }
            }
        }

        String basePackage = extractBasePackage(processName);
        if (!TextUtils.isEmpty(basePackage) && applicationExists(basePackage)) {
            packageNames.add(basePackage);
        }
        if (!TextUtils.isEmpty(processName) && applicationExists(processName)) {
            packageNames.add(processName);
        }
        return packageNames.toArray(new String[0]);
    }

    private String[] getPackagesForUid(int uid) {
        String[] cached = uidPackagesCache.get(Integer.valueOf(uid));
        if (cached != null) {
            return cached;
        }
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages == null) {
            packages = new String[0];
        }
        uidPackagesCache.put(Integer.valueOf(uid), packages);
        return packages;
    }

    private boolean applicationExists(String packageName) {
        Boolean cached = packageExistsCache.get(packageName);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean exists;
        try {
            packageManager.getApplicationInfo(packageName, 0);
            exists = true;
        } catch (PackageManager.NameNotFoundException ignored) {
            exists = false;
        }
        packageExistsCache.put(packageName, Boolean.valueOf(exists));
        return exists;
    }

    private String resolveLabel(String[] packageNames, String processName, int uid) {
        for (String packageName : packageNames) {
            String label = resolveLabel(packageName, null);
            if (!TextUtils.isEmpty(label) && !label.equals(packageName)) {
                return label;
            }
        }

        String basePackage = extractBasePackage(processName);
        if (!TextUtils.isEmpty(basePackage)) {
            String label = resolveLabel(basePackage, null);
            if (!TextUtils.isEmpty(label) && !label.equals(basePackage)) {
                return label;
            }
        }
        if (!TextUtils.isEmpty(processName)) {
            return processName;
        }
        return uid >= 0 ? "uid " + uid : "Unknown process";
    }

    private String resolveLabel(String packageName, String fallback) {
        if (TextUtils.isEmpty(packageName)) {
            return fallback;
        }
        String cached = labelCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        String resolved = fallback;
        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(applicationInfo);
            if (!TextUtils.isEmpty(label)) {
                resolved = label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            resolved = fallback;
        }
        if (resolved == null) {
            resolved = packageName;
        }
        labelCache.put(packageName, resolved);
        return resolved;
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
        int nameStartIndex = line.indexOf('(');
        int nameEndIndex = line.lastIndexOf(')');
        if (nameStartIndex < 0 || nameEndIndex <= nameStartIndex || nameEndIndex + 2 >= line.length()) {
            return null;
        }
        String statName = line.substring(nameStartIndex + 1, nameEndIndex);
        String[] parts = line.substring(nameEndIndex + 2).trim().split("\\s+");
        if (parts.length <= 12) {
            return null;
        }
        Long userTicks = parseLong(parts[11]);
        Long systemTicks = parseLong(parts[12]);
        if (userTicks == null || systemTicks == null) {
            return null;
        }
        return new ProcessCpuSample(userTicks.longValue() + systemTicks.longValue(), statName);
    }

    private ProcStatusSample readProcessStatus(int pid) {
        String name = null;
        String stateText = null;
        int uid = -1;
        long rssKb = 0L;
        long currentVmKb = 0L;
        long peakVmKb = 0L;
        long swapKb = 0L;
        int threadCount = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int colonIndex = line.indexOf(':');
                if (colonIndex <= 0) {
                    continue;
                }
                String key = line.substring(0, colonIndex).trim();
                String rawValue = line.substring(colonIndex + 1).trim();
                if ("Name".equals(key)) {
                    name = rawValue;
                } else if ("State".equals(key)) {
                    stateText = rawValue;
                } else if ("Uid".equals(key)) {
                    String[] parts = rawValue.split("\\s+");
                    if (parts.length > 0) {
                        Integer parsedUid = parseInt(parts[0]);
                        if (parsedUid != null) {
                            uid = parsedUid.intValue();
                        }
                    }
                } else if ("VmRSS".equals(key)) {
                    Long value = parseProcKilobytes(rawValue);
                    if (value != null) {
                        rssKb = value.longValue();
                    }
                } else if ("VmSize".equals(key)) {
                    Long value = parseProcKilobytes(rawValue);
                    if (value != null) {
                        currentVmKb = value.longValue();
                    }
                } else if ("VmPeak".equals(key)) {
                    Long value = parseProcKilobytes(rawValue);
                    if (value != null) {
                        peakVmKb = value.longValue();
                    }
                } else if ("VmSwap".equals(key)) {
                    Long value = parseProcKilobytes(rawValue);
                    if (value != null) {
                        swapKb = value.longValue();
                    }
                } else if ("Threads".equals(key)) {
                    Integer value = parseInt(rawValue);
                    if (value != null) {
                        threadCount = value.intValue();
                    }
                }
            }
        } catch (IOException | SecurityException ignored) {
            return null;
        }

        return new ProcStatusSample(
                name,
                stateText,
                uid,
                rssKb,
                currentVmKb,
                peakVmKb,
                swapKb,
                threadCount);
    }

    private String readProcessName(
            int pid,
            ProcStatusSample statusSample,
            ProcessCpuSample cpuSample) {
        String cmdline = readProcessCommandLine(pid);
        if (!TextUtils.isEmpty(cmdline)) {
            return cmdline;
        }
        if (statusSample != null && !TextUtils.isEmpty(statusSample.name)) {
            return statusSample.name;
        }
        if (cpuSample != null && !TextUtils.isEmpty(cpuSample.statName)) {
            return cpuSample.statName;
        }
        return null;
    }

    private String readProcessCommandLine(int pid) {
        File file = new File("/proc/" + pid + "/cmdline");
        if (!file.isFile() || !file.canRead()) {
            return null;
        }
        byte[] buffer = new byte[4096];
        try (FileInputStream inputStream = new FileInputStream(file)) {
            int readLength = inputStream.read(buffer);
            if (readLength <= 0) {
                return null;
            }
            int endIndex = 0;
            while (endIndex < readLength && buffer[endIndex] != 0) {
                endIndex += 1;
            }
            if (endIndex <= 0) {
                return null;
            }
            String raw = new String(buffer, 0, endIndex, StandardCharsets.UTF_8).trim();
            return raw.isEmpty() ? null : raw;
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private String readFirstLine(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        } catch (IOException | SecurityException ignored) {
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

    private Integer parseInt(String rawValue) {
        try {
            return Integer.valueOf(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer tryParsePositiveInt(String rawValue) {
        Integer parsed = parseInt(rawValue);
        if (parsed == null || parsed.intValue() <= 0) {
            return null;
        }
        return parsed;
    }

    private Long parseProcKilobytes(String rawValue) {
        if (TextUtils.isEmpty(rawValue)) {
            return null;
        }
        String[] parts = rawValue.trim().split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        return parseLong(parts[0]);
    }

    private String extractBasePackage(String processName) {
        if (TextUtils.isEmpty(processName)) {
            return null;
        }
        int separatorIndex = processName.indexOf(':');
        String candidate = separatorIndex >= 0 ? processName.substring(0, separatorIndex) : processName;
        return candidate.contains(".") ? candidate : null;
    }

    private void sortProcessEntries(List<ProcessEntry> entries) {
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
    }

    private void sortAppEntries(List<AppEntry> entries) {
        Collections.sort(entries, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry left, AppEntry right) {
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
    }

    private String formatKilobytes(long kilobytes) {
        return android.text.format.Formatter.formatShortFileSize(context, kilobytes * 1024L);
    }

    private String formatMemoryText(long pssKb, long rssKb) {
        if (pssKb > 0L && rssKb > 0L) {
            return "PSS " + formatKilobytes(pssKb) + " | RSS " + formatKilobytes(rssKb);
        }
        if (pssKb > 0L) {
            return "PSS " + formatKilobytes(pssKb);
        }
        if (rssKb > 0L) {
            return "RSS " + formatKilobytes(rssKb);
        }
        return "Unavailable";
    }

    private String formatAddressSpace(long currentKb, long peakKb, long swapKb, int threadCount) {
        List<String> parts = new ArrayList<>();
        if (currentKb > 0L && peakKb > 0L) {
            parts.add("VSS " + formatKilobytes(currentKb) + " / peak " + formatKilobytes(peakKb));
        } else if (currentKb > 0L) {
            parts.add("VSS " + formatKilobytes(currentKb));
        } else if (peakKb > 0L) {
            parts.add("Peak " + formatKilobytes(peakKb));
        }
        if (swapKb > 0L) {
            parts.add("Swap " + formatKilobytes(swapKb));
        }
        if (threadCount > 0) {
            parts.add("Threads " + threadCount);
        }
        return parts.isEmpty() ? "Unavailable" : TextUtils.join(" | ", parts);
    }

    private boolean isForegroundLike(int importance) {
        return importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }

    private String describeProcessState(int importance, ProcStatusSample statusSample) {
        String importanceText = describeImportance(importance);
        String linuxState = statusSample == null ? null : statusSample.stateText;
        if (importance == IMPORTANCE_UNKNOWN) {
            return nonEmptyOrFallback(linuxState, importanceText);
        }
        if (TextUtils.isEmpty(linuxState)) {
            return importanceText;
        }
        return importanceText + " | " + linuxState;
    }

    private String describeImportance(int importance) {
        if (importance == IMPORTANCE_UNKNOWN) {
            return "Importance unavailable";
        }
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

    private String nonEmptyOrFallback(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }
}
