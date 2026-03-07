package com.micklab.resource_mon;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class DeviceStatsCollector {
    private static final long INITIAL_SAMPLE_DELAY_MS = 250L;
    private static final String CPU_SYSFS_ROOT = "/sys/devices/system/cpu";
    private static final String DEVFREQ_ROOT = "/sys/class/devfreq";

    private final Context context;
    private final ActivityManager activityManager;
    private final ConnectivityManager connectivityManager;
    private final PackageManager packageManager;

    private CpuSample previousCpuSample;
    private NetworkSample previousNetworkSample;
    private GpuInfo gpuInfo = GpuInfo.pending();

    public DeviceStatsCollector(Context context) {
        Context appContext = context.getApplicationContext();
        this.context = appContext;
        this.activityManager =
                (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        this.connectivityManager =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.packageManager = appContext.getPackageManager();
    }

    public synchronized void updateGpuInfo(String vendor, String renderer, String version) {
        gpuInfo = new GpuInfo(
                nonEmptyOrDefault(vendor, "Unavailable"),
                nonEmptyOrDefault(renderer, "Unavailable"),
                nonEmptyOrDefault(version, "Unavailable"));
    }

    public Snapshot collect() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }

        CpuUsageResult cpuUsageResult = sampleCpuUsage();
        NetworkTrafficResult networkTrafficResult = sampleNetworkTraffic();

        return new Snapshot(
                buildOverviewSection(),
                buildCpuSection(cpuUsageResult),
                buildGpuSection(),
                buildNpuSection(),
                buildMemorySection(memoryInfo),
                buildStorageSection(),
                buildNetworkSection(networkTrafficResult),
                System.currentTimeMillis());
    }

    private String buildOverviewSection() {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "Device", Build.MANUFACTURER + " " + Build.MODEL);
        appendLine(builder, "Brand / Product", Build.BRAND + " / " + Build.PRODUCT);
        appendLine(builder, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        appendLine(builder, "SoC", describeSoc());
        appendLine(builder, "Kernel",
                nonEmptyOrDefault(
                        readFirstLine("/proc/version"),
                        nonEmptyOrDefault(System.getProperty("os.version"), "Unavailable")));
        appendLine(builder, "Uptime", DateUtils.formatElapsedTime(SystemClock.elapsedRealtime() / 1000L));
        return builder.toString();
    }

    private String buildCpuSection(CpuUsageResult cpuUsageResult) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "CPU model", describeCpuModel());
        appendLine(builder, "Architecture", nonEmptyOrDefault(System.getProperty("os.arch"), "Unavailable"));
        appendLine(builder, "ABIs", TextUtils.join(", ", Build.SUPPORTED_ABIS));
        appendLine(builder, "Core count", String.valueOf(Runtime.getRuntime().availableProcessors()));
        appendLine(builder, "Frequency", describeCpuFrequency());
        appendLine(builder, "Usage", cpuUsageResult.toDisplayString());
        appendLine(builder, "Load avg", describeLoadAverage());
        return builder.toString();
    }

    private String buildGpuSection() {
        StringBuilder builder = new StringBuilder();
        GpuInfo currentGpuInfo = getGpuInfo();
        appendLine(builder, "Renderer", currentGpuInfo.renderer);
        appendLine(builder, "Vendor", currentGpuInfo.vendor);
        appendLine(builder, "Driver", currentGpuInfo.version);

        if (activityManager != null) {
            ConfigurationInfo configurationInfo = activityManager.getDeviceConfigurationInfo();
            appendLine(builder, "OpenGL ES",
                    configurationInfo == null ? "Unavailable" : configurationInfo.getGlEsVersion());
        }
        appendLine(builder, "Extension pack",
                yesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK)));
        appendLine(builder, "Vulkan features",
                describeFeatures(new String[]{"vulkan"}, "No Vulkan feature flags advertised"));

        DevfreqSnapshot gpuProbe = probeDevfreq(new String[]{"gpu", "kgsl", "mali", "adreno"});
        appendLine(builder, "Devfreq node", gpuProbe.nodeName != null ? gpuProbe.nodeName : gpuProbe.message);
        if (!TextUtils.isEmpty(gpuProbe.governor)) {
            appendLine(builder, "Governor", gpuProbe.governor);
        }
        if (!TextUtils.isEmpty(gpuProbe.currentFrequency)) {
            appendLine(builder, "Current freq", gpuProbe.currentFrequency);
        }
        if (!TextUtils.isEmpty(gpuProbe.maxFrequency)) {
            appendLine(builder, "Top freq", gpuProbe.maxFrequency);
        }
        appendLine(builder, "Usage",
                nonEmptyOrDefault(gpuProbe.utilization, "Unavailable via public APIs / readable sysfs nodes"));
        return builder.toString();
    }

    private String buildNpuSection() {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "NNAPI runtime",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 ? "Available in OS" : "Unavailable in OS");
        appendLine(builder, "Advertised features",
                describeFeatures(
                        new String[]{"neural", "nnapi", "npu", "apu", "mdla", "edgetpu"},
                        "No neural accelerator features advertised"));

        DevfreqSnapshot npuProbe = probeDevfreq(new String[]{"npu", "apu", "neural", "mdla", "edgetpu"});
        appendLine(builder, "Devfreq node", npuProbe.nodeName != null ? npuProbe.nodeName : npuProbe.message);
        if (!TextUtils.isEmpty(npuProbe.governor)) {
            appendLine(builder, "Governor", npuProbe.governor);
        }
        if (!TextUtils.isEmpty(npuProbe.currentFrequency)) {
            appendLine(builder, "Current freq", npuProbe.currentFrequency);
        }
        if (!TextUtils.isEmpty(npuProbe.maxFrequency)) {
            appendLine(builder, "Top freq", npuProbe.maxFrequency);
        }
        appendLine(builder, "Usage",
                nonEmptyOrDefault(npuProbe.utilization, "Unavailable from public Android APIs / readable sysfs nodes"));
        return builder.toString();
    }

    private String buildMemorySection(ActivityManager.MemoryInfo memoryInfo) {
        StringBuilder builder = new StringBuilder();
        if (memoryInfo.totalMem > 0L) {
            long usedMemory = memoryInfo.totalMem - memoryInfo.availMem;
            appendLine(builder, "Total / Used / Free",
                    formatBytes(memoryInfo.totalMem) + " / "
                            + formatBytes(usedMemory) + " / "
                            + formatBytes(memoryInfo.availMem));
            appendLine(builder, "Usage",
                    formatPercent((double) usedMemory / (double) memoryInfo.totalMem));
            appendLine(builder, "Low-memory threshold", formatBytes(memoryInfo.threshold));
            appendLine(builder, "Low-memory state", yesNo(memoryInfo.lowMemory));
        } else {
            appendLine(builder, "RAM", "Memory service unavailable");
        }

        Runtime runtime = Runtime.getRuntime();
        long appHeapUsed = runtime.totalMemory() - runtime.freeMemory();
        appendLine(builder, "App heap", formatBytes(appHeapUsed) + " / " + formatBytes(runtime.maxMemory()));
        return builder.toString();
    }

    private String buildStorageSection() {
        StringBuilder builder = new StringBuilder();
        File dataDirectory = Environment.getDataDirectory();
        StatFs statFs = new StatFs(dataDirectory.getAbsolutePath());
        long totalBytes = statFs.getTotalBytes();
        long availableBytes = statFs.getAvailableBytes();
        long usedBytes = totalBytes - availableBytes;

        appendLine(builder, "Path", dataDirectory.getAbsolutePath());
        appendLine(builder, "Total / Used / Free",
                formatBytes(totalBytes) + " / " + formatBytes(usedBytes) + " / " + formatBytes(availableBytes));
        appendLine(builder, "Usage",
                formatPercent(totalBytes > 0L ? (double) usedBytes / (double) totalBytes : Double.NaN));
        appendLine(builder, "Block size", formatBytes(statFs.getBlockSizeLong()));
        return builder.toString();
    }

    private String buildNetworkSection(NetworkTrafficResult trafficResult) {
        StringBuilder builder = new StringBuilder();
        if (connectivityManager == null) {
            appendLine(builder, "Status", "Connectivity service unavailable");
            appendLine(builder, "Device Rx / Tx", trafficResult.totalBytesDisplay(context));
            appendLine(builder, "Current Rx / Tx", trafficResult.rateDisplay(context));
            return builder.toString();
        }

        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            appendLine(builder, "Status", "Disconnected");
        } else {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
            appendLine(builder, "Status",
                    capabilities == null ? "Connected" : "Connected (" + describeTransports(capabilities) + ")");
            if (capabilities != null) {
                appendLine(builder, "Validated",
                        yesNo(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));
                appendLine(builder, "Metered",
                        yesNo(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)));
                appendLine(builder, "Roaming",
                        yesNo(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)));
                appendLine(builder, "Bandwidth", describeBandwidth(capabilities));
            }
            if (linkProperties != null) {
                appendLine(builder, "Interface",
                        nonEmptyOrDefault(linkProperties.getInterfaceName(), "Unavailable"));
            }
        }

        appendLine(builder, "Device Rx / Tx", trafficResult.totalBytesDisplay(context));
        appendLine(builder, "Current Rx / Tx", trafficResult.rateDisplay(context));
        return builder.toString();
    }

    private synchronized GpuInfo getGpuInfo() {
        return gpuInfo;
    }

    private CpuUsageResult sampleCpuUsage() {
        CpuSample currentSample = readCpuSample();
        if (currentSample == null) {
            return CpuUsageResult.unavailable("Unable to read /proc/stat");
        }
        if (previousCpuSample == null) {
            previousCpuSample = currentSample;
            SystemClock.sleep(INITIAL_SAMPLE_DELAY_MS);
            currentSample = readCpuSample();
            if (currentSample == null) {
                return CpuUsageResult.unavailable("Unable to resample /proc/stat");
            }
        }

        CpuSample baseline = previousCpuSample;
        previousCpuSample = currentSample;

        long totalDelta = currentSample.total - baseline.total;
        long idleDelta = currentSample.idle - baseline.idle;
        if (totalDelta <= 0L) {
            return CpuUsageResult.unavailable("Sampling window too small");
        }
        double usageRatio = (double) (totalDelta - idleDelta) / (double) totalDelta;
        return CpuUsageResult.available(usageRatio);
    }

    private NetworkTrafficResult sampleNetworkTraffic() {
        long rxBytes = TrafficStats.getTotalRxBytes();
        long txBytes = TrafficStats.getTotalTxBytes();
        if (rxBytes == TrafficStats.UNSUPPORTED || txBytes == TrafficStats.UNSUPPORTED) {
            return NetworkTrafficResult.unsupported();
        }

        NetworkSample currentSample = new NetworkSample(rxBytes, txBytes, SystemClock.elapsedRealtime());
        if (previousNetworkSample == null) {
            previousNetworkSample = currentSample;
            SystemClock.sleep(INITIAL_SAMPLE_DELAY_MS);
            currentSample = new NetworkSample(
                    TrafficStats.getTotalRxBytes(),
                    TrafficStats.getTotalTxBytes(),
                    SystemClock.elapsedRealtime());
        }

        NetworkSample baseline = previousNetworkSample;
        previousNetworkSample = currentSample;

        long elapsedDelta = currentSample.elapsedRealtimeMs - baseline.elapsedRealtimeMs;
        if (elapsedDelta <= 0L) {
            return new NetworkTrafficResult(
                    currentSample.rxBytes,
                    currentSample.txBytes,
                    Double.NaN,
                    Double.NaN,
                    "Sampling window too small");
        }

        double rxPerSecond = ((double) (currentSample.rxBytes - baseline.rxBytes) * 1000.0d) / (double) elapsedDelta;
        double txPerSecond = ((double) (currentSample.txBytes - baseline.txBytes) * 1000.0d) / (double) elapsedDelta;
        return new NetworkTrafficResult(currentSample.rxBytes, currentSample.txBytes, rxPerSecond, txPerSecond, null);
    }

    private CpuSample readCpuSample() {
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
            Long value = tryParseLong(parts[index]);
            if (value == null) {
                return null;
            }
            total += value.longValue();
        }

        Long idle = tryParseLong(parts[4]);
        if (idle == null) {
            return null;
        }
        if (parts.length > 5) {
            Long iowait = tryParseLong(parts[5]);
            if (iowait != null) {
                idle = Long.valueOf(idle.longValue() + iowait.longValue());
            }
        }
        return new CpuSample(total, idle.longValue());
    }

    private String describeCpuModel() {
        String cpuInfo = readKeyValueFromFile("/proc/cpuinfo",
                new String[]{"model name", "Hardware", "Processor"});
        if (!TextUtils.isEmpty(cpuInfo)) {
            return cpuInfo;
        }
        return describeSoc();
    }

    private String describeSoc() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String manufacturer = Build.SOC_MANUFACTURER;
            String model = Build.SOC_MODEL;
            if (!TextUtils.isEmpty(manufacturer) || !TextUtils.isEmpty(model)) {
                return nonEmptyOrDefault(manufacturer, "Unknown") + " "
                        + nonEmptyOrDefault(model, "Unknown");
            }
        }
        return Build.HARDWARE + " / " + Build.BOARD;
    }

    private String describeCpuFrequency() {
        File cpuRoot = new File(CPU_SYSFS_ROOT);
        File[] cpuEntries = cpuRoot.listFiles();
        if (cpuEntries == null || cpuEntries.length == 0) {
            return "Unavailable";
        }

        long minimumKHz = Long.MAX_VALUE;
        long maximumKHz = Long.MIN_VALUE;
        long totalCurrentKHz = 0L;
        int currentCount = 0;

        for (File entry : cpuEntries) {
            if (!entry.isDirectory() || !entry.getName().matches("cpu\\d+")) {
                continue;
            }
            File cpufreqDir = new File(entry, "cpufreq");
            Long minimum = readLongFromCandidates(cpufreqDir,
                    new String[]{"scaling_min_freq", "cpuinfo_min_freq"});
            Long maximum = readLongFromCandidates(cpufreqDir,
                    new String[]{"scaling_max_freq", "cpuinfo_max_freq"});
            Long current = readLongFromCandidates(cpufreqDir,
                    new String[]{"scaling_cur_freq", "cpuinfo_cur_freq"});

            if (minimum != null) {
                minimumKHz = Math.min(minimumKHz, minimum.longValue());
            }
            if (maximum != null) {
                maximumKHz = Math.max(maximumKHz, maximum.longValue());
            }
            if (current != null) {
                totalCurrentKHz += current.longValue();
                currentCount++;
            }
        }

        if (minimumKHz == Long.MAX_VALUE && maximumKHz == Long.MIN_VALUE && currentCount == 0) {
            return "Unavailable";
        }

        StringBuilder builder = new StringBuilder();
        if (currentCount > 0) {
            builder.append("avg ").append(formatKilohertz(totalCurrentKHz / currentCount));
        }
        if (minimumKHz != Long.MAX_VALUE || maximumKHz != Long.MIN_VALUE) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append("range ")
                    .append(minimumKHz == Long.MAX_VALUE ? "?" : formatKilohertz(minimumKHz))
                    .append(" - ")
                    .append(maximumKHz == Long.MIN_VALUE ? "?" : formatKilohertz(maximumKHz));
        }
        return builder.toString();
    }

    private String describeLoadAverage() {
        String loadAverage = readFirstLine("/proc/loadavg");
        if (TextUtils.isEmpty(loadAverage)) {
            return "Unavailable";
        }
        String[] parts = loadAverage.trim().split("\\s+");
        if (parts.length < 3) {
            return loadAverage;
        }
        return parts[0] + " / " + parts[1] + " / " + parts[2];
    }

    private String describeFeatures(String[] keywords, String emptyMessage) {
        FeatureInfo[] features = packageManager.getSystemAvailableFeatures();
        if (features == null || features.length == 0) {
            return emptyMessage;
        }

        List<String> matches = new ArrayList<>();
        for (FeatureInfo feature : features) {
            if (feature == null || TextUtils.isEmpty(feature.name)) {
                continue;
            }
            String lowerName = feature.name.toLowerCase(Locale.US);
            if (containsKeyword(lowerName, keywords)) {
                matches.add(feature.name);
            }
        }
        if (matches.isEmpty()) {
            return emptyMessage;
        }
        return TextUtils.join(", ", matches);
    }

    private String describeTransports(NetworkCapabilities capabilities) {
        List<String> transports = new ArrayList<>();
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transports.add("Wi-Fi");
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transports.add("Cellular");
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transports.add("Ethernet");
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            transports.add("Bluetooth");
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            transports.add("VPN");
        }
        if (transports.isEmpty()) {
            return "Unknown";
        }
        return TextUtils.join(", ", transports);
    }

    private String describeBandwidth(NetworkCapabilities capabilities) {
        return formatBandwidth(capabilities.getLinkDownstreamBandwidthKbps())
                + " down / "
                + formatBandwidth(capabilities.getLinkUpstreamBandwidthKbps())
                + " up";
    }

    private DevfreqSnapshot probeDevfreq(String[] keywords) {
        File devfreqRoot = new File(DEVFREQ_ROOT);
        File[] nodes = devfreqRoot.listFiles();
        if (nodes == null || nodes.length == 0) {
            return DevfreqSnapshot.unavailable("No readable /sys/class/devfreq nodes");
        }

        Arrays.sort(nodes, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });

        boolean matchedNodeExists = false;
        for (File node : nodes) {
            if (!containsKeyword(node.getName().toLowerCase(Locale.US), keywords)) {
                continue;
            }
            matchedNodeExists = true;

            ProbeText governor = readFirstMatchingFile(node, new String[]{"governor"});
            ProbeText currentFrequency = readFirstMatchingFile(node, new String[]{"cur_freq"});
            ProbeText maxFrequency = readFirstMatchingFile(node, new String[]{"max_freq"});
            if (maxFrequency == null) {
                maxFrequency = readAvailableFrequencySummary(node);
            }
            ProbeText utilization = readFirstMatchingFile(node,
                    new String[]{"gpu_busy_percentage", "busy_percent", "utilization", "load"});

            if (governor != null || currentFrequency != null || maxFrequency != null || utilization != null) {
                return new DevfreqSnapshot(
                        node.getName(),
                        governor == null ? null : governor.value,
                        currentFrequency == null ? null : describeFrequencyProbe(currentFrequency),
                        maxFrequency == null ? null : describeFrequencyProbe(maxFrequency),
                        utilization == null ? null : describeUtilizationProbe(utilization),
                        null);
            }
        }

        if (matchedNodeExists) {
            return DevfreqSnapshot.unavailable("Matching nodes exist but expose no readable metrics");
        }
        return DevfreqSnapshot.unavailable("No matching nodes found");
    }

    private ProbeText readAvailableFrequencySummary(File node) {
        ProbeText availableFrequencies = readFirstMatchingFile(node, new String[]{"available_frequencies"});
        if (availableFrequencies == null || TextUtils.isEmpty(availableFrequencies.value)) {
            return null;
        }

        String[] parts = availableFrequencies.value.trim().split("\\s+");
        long highestValue = Long.MIN_VALUE;
        for (String part : parts) {
            Long numericValue = tryParseLong(part);
            if (numericValue != null) {
                highestValue = Math.max(highestValue, numericValue.longValue());
            }
        }
        if (highestValue == Long.MIN_VALUE) {
            return null;
        }
        return new ProbeText(availableFrequencies.fileName, String.valueOf(highestValue));
    }

    private ProbeText readFirstMatchingFile(File directory, String[] fileNames) {
        if (!directory.isDirectory()) {
            return null;
        }
        for (String fileName : fileNames) {
            File candidate = new File(directory, fileName);
            if (!candidate.isFile() || !candidate.canRead()) {
                continue;
            }
            String value = readFirstLine(candidate.getAbsolutePath());
            if (!TextUtils.isEmpty(value)) {
                return new ProbeText(fileName, value);
            }
        }
        return null;
    }

    private Long readLongFromCandidates(File directory, String[] fileNames) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        for (String fileName : fileNames) {
            String value = readFirstLine(new File(directory, fileName).getAbsolutePath());
            if (TextUtils.isEmpty(value)) {
                continue;
            }
            Long numericValue = tryParseLong(value);
            if (numericValue != null) {
                return numericValue;
            }
        }
        return null;
    }

    private String readKeyValueFromFile(String filePath, String[] keys) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separatorIndex = line.indexOf(':');
                if (separatorIndex <= 0) {
                    continue;
                }
                String key = line.substring(0, separatorIndex).trim();
                String value = line.substring(separatorIndex + 1).trim();
                for (String wantedKey : keys) {
                    if (wantedKey.equalsIgnoreCase(key) && !TextUtils.isEmpty(value)) {
                        return value;
                    }
                }
            }
        } catch (IOException | SecurityException exception) {
            return null;
        }
        return null;
    }

    private String readFirstLine(String filePath) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return trimmed;
        } catch (IOException | SecurityException exception) {
            return null;
        }
    }

    private String formatBytes(long byteCount) {
        return Formatter.formatShortFileSize(context, byteCount);
    }

    private static String formatBytes(Context context, long byteCount) {
        return Formatter.formatShortFileSize(context, byteCount);
    }

    private static String formatRate(Context context, double bytesPerSecond) {
        if (Double.isNaN(bytesPerSecond) || bytesPerSecond < 0.0d) {
            return "Measuring...";
        }
        return Formatter.formatShortFileSize(context, Math.round(bytesPerSecond)) + "/s";
    }

    private static String describeFrequencyProbe(ProbeText probeText) {
        Long numericValue = tryParseLong(probeText.value);
        if (numericValue == null) {
            return probeText.value + " (" + probeText.fileName + ")";
        }
        return formatFrequencyGuess(numericValue.longValue()) + " (" + probeText.fileName + ")";
    }

    private static String describeUtilizationProbe(ProbeText probeText) {
        if ("gpu_busy_percentage".equals(probeText.fileName)
                || "busy_percent".equals(probeText.fileName)) {
            return probeText.value + "% (" + probeText.fileName + ")";
        }
        return probeText.value + " (" + probeText.fileName + ")";
    }

    private static String formatPercent(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            return "Unavailable";
        }
        return String.format(Locale.US, "%.1f%%", ratio * 100.0d);
    }

    private static String formatKilohertz(long kilohertz) {
        double megahertz = kilohertz / 1000.0d;
        if (megahertz >= 1000.0d) {
            return String.format(Locale.US, "%.2f GHz", megahertz / 1000.0d);
        }
        return String.format(Locale.US, "%.0f MHz", megahertz);
    }

    private static String formatFrequencyGuess(long rawFrequency) {
        if (rawFrequency >= 1_000_000_000L) {
            return String.format(Locale.US, "%.2f GHz", rawFrequency / 1_000_000_000.0d);
        }
        if (rawFrequency >= 1_000_000L) {
            return String.format(Locale.US, "%.0f MHz", rawFrequency / 1_000_000.0d);
        }
        if (rawFrequency >= 1_000L) {
            return String.format(Locale.US, "%.0f kHz", rawFrequency / 1000.0d);
        }
        return rawFrequency + " Hz";
    }

    private static String formatBandwidth(int kilobitsPerSecond) {
        if (kilobitsPerSecond <= 0) {
            return "Unknown";
        }
        if (kilobitsPerSecond >= 1000) {
            return String.format(Locale.US, "%.1f Mbps", kilobitsPerSecond / 1000.0d);
        }
        return kilobitsPerSecond + " Kbps";
    }

    private static boolean containsKeyword(String value, String[] keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    private static String nonEmptyOrDefault(String value, String fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        return value;
    }

    private static Long tryParseLong(String rawValue) {
        if (TextUtils.isEmpty(rawValue)) {
            return null;
        }
        try {
            return Long.valueOf(rawValue.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static final class Snapshot {
        public final String overview;
        public final String cpu;
        public final String gpu;
        public final String npu;
        public final String memory;
        public final String storage;
        public final String network;
        public final long collectedAtMillis;

        public Snapshot(
                String overview,
                String cpu,
                String gpu,
                String npu,
                String memory,
                String storage,
                String network,
                long collectedAtMillis) {
            this.overview = overview;
            this.cpu = cpu;
            this.gpu = gpu;
            this.npu = npu;
            this.memory = memory;
            this.storage = storage;
            this.network = network;
            this.collectedAtMillis = collectedAtMillis;
        }
    }

    private static final class GpuInfo {
        final String vendor;
        final String renderer;
        final String version;

        GpuInfo(String vendor, String renderer, String version) {
            this.vendor = vendor;
            this.renderer = renderer;
            this.version = version;
        }

        static GpuInfo pending() {
            return new GpuInfo("Pending GPU probe", "Pending GPU probe", "Pending GPU probe");
        }
    }

    private static final class CpuSample {
        final long total;
        final long idle;

        CpuSample(long total, long idle) {
            this.total = total;
            this.idle = idle;
        }
    }

    private static final class CpuUsageResult {
        final Double usageRatio;
        final String message;

        CpuUsageResult(Double usageRatio, String message) {
            this.usageRatio = usageRatio;
            this.message = message;
        }

        static CpuUsageResult available(double usageRatio) {
            return new CpuUsageResult(Double.valueOf(usageRatio), null);
        }

        static CpuUsageResult unavailable(String message) {
            return new CpuUsageResult(null, message);
        }

        String toDisplayString() {
            if (usageRatio != null) {
                return formatPercent(usageRatio.doubleValue());
            }
            return nonEmptyOrDefault(message, "Unavailable");
        }
    }

    private static final class NetworkSample {
        final long rxBytes;
        final long txBytes;
        final long elapsedRealtimeMs;

        NetworkSample(long rxBytes, long txBytes, long elapsedRealtimeMs) {
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
            this.elapsedRealtimeMs = elapsedRealtimeMs;
        }
    }

    private static final class NetworkTrafficResult {
        final long rxBytes;
        final long txBytes;
        final double rxPerSecond;
        final double txPerSecond;
        final String message;

        NetworkTrafficResult(long rxBytes, long txBytes, double rxPerSecond, double txPerSecond, String message) {
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
            this.rxPerSecond = rxPerSecond;
            this.txPerSecond = txPerSecond;
            this.message = message;
        }

        static NetworkTrafficResult unsupported() {
            return new NetworkTrafficResult(TrafficStats.UNSUPPORTED, TrafficStats.UNSUPPORTED, Double.NaN, Double.NaN,
                    "Unsupported by this device");
        }

        String totalBytesDisplay(Context context) {
            if (rxBytes == TrafficStats.UNSUPPORTED || txBytes == TrafficStats.UNSUPPORTED) {
                return message;
            }
            return formatBytes(context, rxBytes) + " / " + formatBytes(context, txBytes);
        }

        String rateDisplay(Context context) {
            if (!TextUtils.isEmpty(message) && (rxBytes == TrafficStats.UNSUPPORTED || txBytes == TrafficStats.UNSUPPORTED)) {
                return message;
            }
            if (!TextUtils.isEmpty(message) && Double.isNaN(rxPerSecond) && Double.isNaN(txPerSecond)) {
                return message;
            }
            return formatRate(context, rxPerSecond) + " / " + formatRate(context, txPerSecond);
        }
    }

    private static final class DevfreqSnapshot {
        final String nodeName;
        final String governor;
        final String currentFrequency;
        final String maxFrequency;
        final String utilization;
        final String message;

        DevfreqSnapshot(
                String nodeName,
                String governor,
                String currentFrequency,
                String maxFrequency,
                String utilization,
                String message) {
            this.nodeName = nodeName;
            this.governor = governor;
            this.currentFrequency = currentFrequency;
            this.maxFrequency = maxFrequency;
            this.utilization = utilization;
            this.message = message;
        }

        static DevfreqSnapshot unavailable(String message) {
            return new DevfreqSnapshot(null, null, null, null, null, message);
        }
    }

    private static final class ProbeText {
        final String fileName;
        final String value;

        ProbeText(String fileName, String value) {
            this.fileName = fileName;
            this.value = value;
        }
    }

    private static void appendLine(StringBuilder builder, String label, String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(label).append(": ").append(value);
    }
}
