package com.micklab.rm;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class ProcessDetailsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshContent();
            handler.postDelayed(this, 2000L);
        }
    };

    private MonitorStore monitorStore;
    private ProcessInspector processInspector;

    private TextView summaryView;
    private TextView memoryDetailView;
    private TextView processNoteView;
    private Button usageAccessButton;
    private TextView foregroundAppListView;
    private TextView backgroundAppListView;
    private TextView foregroundProcessListView;
    private TextView backgroundProcessListView;
    private TextView recentAppsView;
    private TextView foregroundHistoryView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        monitorStore = MonitorStore.get(this);
        processInspector = new ProcessInspector(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        setContentView(buildContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshContent();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private ScrollView buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        final int basePadding = dp(16);

        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(basePadding, basePadding, basePadding, basePadding);
        scrollView.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        scrollView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                container.setPadding(
                        basePadding + insets.getSystemWindowInsetLeft(),
                        basePadding + insets.getSystemWindowInsetTop(),
                        basePadding + insets.getSystemWindowInsetRight(),
                        basePadding + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        scrollView.requestApplyInsets();

        TextView titleView = new TextView(this);
        titleView.setText("Memory / Process details");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        container.addView(titleView);

        summaryView = addSection(container, "Status");
        memoryDetailView = addSection(container, "Detailed RAM");
        processNoteView = addSection(container, "Access note");

        usageAccessButton = new Button(this);
        usageAccessButton.setText("Open usage access settings");
        usageAccessButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(processInspector.buildUsageAccessIntent()));
            }
        });
        LinearLayout.LayoutParams buttonParams = matchParentLayoutParams();
        buttonParams.topMargin = dp(8);
        container.addView(usageAccessButton, buttonParams);

        foregroundAppListView = addSection(container, "Real-time foreground apps");
        backgroundAppListView = addSection(container, "Real-time background apps");
        foregroundProcessListView = addSection(container, "Real-time foreground processes");
        backgroundProcessListView = addSection(container, "Real-time background processes");
        recentAppsView = addSection(container, "Recent foreground apps (usage access)");
        foregroundHistoryView = addSection(container, "Foreground process history");
        return scrollView;
    }

    private void refreshContent() {
        MetricsSampler.MetricsSnapshot latestSample = monitorStore.getLatestSample();
        memoryDetailView.setText(buildMemoryDetailText(latestSample));

        ProcessInspector.ProcessReport report = processInspector.collect();
        processNoteView.setText(report.note);
        summaryView.setText(buildSummaryText(latestSample, report));
        updateUsageAccessButton(report.usageAccessGranted);
        foregroundAppListView.setText(buildAppListText(
                filterForegroundApps(report.appEntries, true),
                "No foreground app CPU/RAM usage is visible right now."));
        backgroundAppListView.setText(buildAppListText(
                filterForegroundApps(report.appEntries, false),
                "No background app CPU/RAM usage is visible right now."));
        foregroundProcessListView.setText(buildProcessListText(
                filterForegroundProcesses(report.processEntries, true),
                "No foreground process CPU/RAM usage is visible right now."));
        backgroundProcessListView.setText(buildProcessListText(
                filterForegroundProcesses(report.processEntries, false),
                "No background process CPU/RAM usage is visible right now."));
        recentAppsView.setText(buildRecentAppsText(report.recentApps, report.appEntries));
        foregroundHistoryView.setText(buildForegroundHistoryText(monitorStore.getForegroundProcessHistory()));
    }

    private String buildSummaryText(
            MetricsSampler.MetricsSnapshot latestSample,
            ProcessInspector.ProcessReport report) {
        if (latestSample == null) {
            return "No recorded samples yet."
                    + "\nUsage access: " + (report.usageAccessGranted ? "ON" : "OFF");
        }
        return "Background recording: " + (monitorStore.isMonitoringEnabled() ? "ON" : "OFF")
                + "\nLast sample: " + DateUtils.getRelativeTimeSpanString(
                        latestSample.timestampMillis,
                        System.currentTimeMillis(),
                        DateUtils.SECOND_IN_MILLIS)
                + "\nCPU: " + formatPercent(latestSample.cpuUsagePercent)
                + " | RAM: " + formatPercent(latestSample.ramUsagePercent())
                + "\nUsage access: " + (report.usageAccessGranted ? "ON" : "OFF")
                + " | Foreground apps: " + filterForegroundApps(report.appEntries, true).size()
                + " | Background apps: " + filterForegroundApps(report.appEntries, false).size();
    }

    private String buildMemoryDetailText(MetricsSampler.MetricsSnapshot latestSample) {
        if (latestSample == null || latestSample.memoryDetails == null) {
            return "Memory detail is not available yet.";
        }
        MetricsSampler.MemoryDetails details = latestSample.memoryDetails;
        List<String> lines = new ArrayList<>();
        lines.add("Total: " + Formatter.formatShortFileSize(this, details.totalBytes));
        lines.add("Used: " + Formatter.formatShortFileSize(this, details.usedBytes));
        lines.add("Available: " + Formatter.formatShortFileSize(this, details.availableBytes));
        lines.add("Free: " + Formatter.formatShortFileSize(this, details.freeBytes));
        lines.add("Buffers: " + Formatter.formatShortFileSize(this, details.buffersBytes));
        lines.add("Cached: " + Formatter.formatShortFileSize(this, details.cachedBytes));
        lines.add("Reclaimable slab: " + Formatter.formatShortFileSize(this, details.reclaimableSlabBytes));
        lines.add("Unreclaimable slab: " + Formatter.formatShortFileSize(this, details.unreclaimableSlabBytes));
        lines.add("Slab total: " + Formatter.formatShortFileSize(this, details.slabBytes));
        lines.add("Active: " + Formatter.formatShortFileSize(this, details.activeBytes));
        lines.add("Inactive: " + Formatter.formatShortFileSize(this, details.inactiveBytes));
        lines.add("Shared memory: " + Formatter.formatShortFileSize(this, details.shmemBytes));
        lines.add("Swap cached: " + Formatter.formatShortFileSize(this, details.swapCachedBytes));
        if (details.swapTotalBytes > 0L) {
            lines.add("Swap used / total: "
                    + Formatter.formatShortFileSize(this, details.swapUsedBytes())
                    + " / "
                    + Formatter.formatShortFileSize(this, details.swapTotalBytes));
        }
        if (details.commitLimitBytes > 0L) {
            lines.add("Address space commit / limit: "
                    + Formatter.formatShortFileSize(this, details.committedBytes)
                    + " / "
                    + Formatter.formatShortFileSize(this, details.commitLimitBytes)
                    + " (" + formatPercent(details.committedPercent()) + ")");
        }
        if (details.vmallocTotalBytes > 0L) {
            lines.add("Kernel vmap used / total: "
                    + Formatter.formatShortFileSize(this, details.vmallocUsedBytes)
                    + " / "
                    + Formatter.formatShortFileSize(this, details.vmallocTotalBytes));
        }
        return TextUtils.join("\n", lines);
    }

    private String buildProcessListText(
            List<ProcessInspector.ProcessEntry> processEntries,
            String emptyMessage) {
        if (processEntries == null || processEntries.isEmpty()) {
            return emptyMessage;
        }
        List<String> lines = new ArrayList<>();
        for (ProcessInspector.ProcessEntry processEntry : processEntries) {
            StringBuilder builder = new StringBuilder();
            builder.append(processEntry.label)
                    .append(" (pid ").append(processEntry.pid)
                    .append(", uid ").append(processEntry.uid).append(')');
            if (processEntry.hasImportance()) {
                builder.append(" | Importance ").append(processEntry.importance);
            }
            builder.append("\nState: ").append(processEntry.stateText);
            builder.append("\nCPU: ").append(processEntry.cpuText)
                    .append(" | Memory: ").append(processEntry.memoryText);
            builder.append("\nAddress space: ").append(processEntry.addressSpaceText);
            builder.append("\nLinux state: ").append(processEntry.linuxStateText);
            builder.append("\nProcess: ").append(processEntry.processName);
            builder.append("\nPackages: ").append(processEntry.packageSummary);
            lines.add(builder.toString());
        }
        return TextUtils.join("\n\n", lines);
    }

    private String buildAppListText(
            List<ProcessInspector.AppEntry> appEntries,
            String emptyMessage) {
        if (appEntries == null || appEntries.isEmpty()) {
            return emptyMessage;
        }
        List<String> lines = new ArrayList<>();
        for (ProcessInspector.AppEntry appEntry : appEntries) {
            StringBuilder builder = new StringBuilder();
            builder.append(appEntry.label)
                    .append(" (uid ").append(appEntry.uid).append(')');
            builder.append("\nProcesses: ").append(appEntry.processCount);
            if (appEntry.hasImportance()) {
                builder.append(" | Importance ").append(appEntry.importance);
            }
            builder.append("\nState: ").append(appEntry.stateText);
            builder.append("\nCPU: ").append(appEntry.cpuText)
                    .append(" | Memory: ").append(appEntry.memoryText);
            builder.append("\nAddress space: ").append(appEntry.addressSpaceText);
            builder.append("\nPackages: ").append(appEntry.packageSummary);
            lines.add(builder.toString());
        }
        return TextUtils.join("\n\n", lines);
    }

    private String buildRecentAppsText(
            List<ProcessInspector.RecentAppEntry> recentApps,
            List<ProcessInspector.AppEntry> liveAppEntries) {
        if (recentApps == null || recentApps.isEmpty()) {
            return "Grant usage access to list recently foregrounded apps here.";
        }
        List<String> lines = new ArrayList<>();
        for (ProcessInspector.RecentAppEntry recentApp : recentApps) {
            StringBuilder builder = new StringBuilder();
            builder.append(recentApp.label)
                    .append("\nPackage: ").append(recentApp.packageName)
                    .append("\nLast used: ").append(DateUtils.getRelativeTimeSpanString(
                            recentApp.lastTimeUsed,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS))
                    .append(" | Foreground time: ")
                    .append(DateUtils.formatElapsedTime(recentApp.totalForegroundTimeMs / 1000L));

            ProcessInspector.AppEntry liveApp = findLiveAppEntry(recentApp.packageName, liveAppEntries);
            if (liveApp != null) {
                builder.append("\nLive state: ").append(liveApp.stateText)
                        .append("\nLive CPU: ").append(liveApp.cpuText)
                        .append(" | Live memory: ").append(liveApp.memoryText);
            }
            lines.add(builder.toString());
        }
        return TextUtils.join("\n\n", lines);
    }

    private String buildForegroundHistoryText(List<MonitorStore.ForegroundProcessStats> foregroundHistory) {
        if (foregroundHistory == null || foregroundHistory.isEmpty()) {
            return "No foreground process history has been recorded yet. Leave monitoring on and switch between apps to accumulate averages.";
        }
        List<String> lines = new ArrayList<>();
        for (MonitorStore.ForegroundProcessStats stats : foregroundHistory) {
            String averageCpuText = stats.cpuSampleCount > 0
                    ? formatPercent(stats.averageCpuPercent)
                    : "Unavailable";
            String averageMemoryText = stats.memorySampleCount > 0
                    ? Formatter.formatShortFileSize(this, stats.averageMemoryKb * 1024L)
                    : "Unavailable";
            lines.add(stats.label
                    + "\nProcess: " + stats.processName
                    + "\nAvg CPU: " + averageCpuText
                    + " | Avg memory: " + averageMemoryText
                    + "\nForeground samples: " + stats.sampleCount
                    + " | Last seen: " + DateUtils.getRelativeTimeSpanString(
                            stats.lastSeenTimestamp,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS)
                    + "\nPackages: " + stats.packageSummary);
        }
        return TextUtils.join("\n\n", lines);
    }

    private void updateUsageAccessButton(boolean usageAccessGranted) {
        usageAccessButton.setEnabled(!usageAccessGranted);
        usageAccessButton.setText(
                usageAccessGranted
                        ? "Usage access enabled"
                        : "Open usage access settings");
    }

    private List<ProcessInspector.AppEntry> filterForegroundApps(
            List<ProcessInspector.AppEntry> appEntries,
            boolean foregroundLike) {
        List<ProcessInspector.AppEntry> filtered = new ArrayList<>();
        if (appEntries == null) {
            return filtered;
        }
        for (ProcessInspector.AppEntry appEntry : appEntries) {
            if (appEntry != null && appEntry.foregroundLike == foregroundLike) {
                filtered.add(appEntry);
            }
        }
        return filtered;
    }

    private List<ProcessInspector.ProcessEntry> filterForegroundProcesses(
            List<ProcessInspector.ProcessEntry> processEntries,
            boolean foregroundLike) {
        List<ProcessInspector.ProcessEntry> filtered = new ArrayList<>();
        if (processEntries == null) {
            return filtered;
        }
        for (ProcessInspector.ProcessEntry processEntry : processEntries) {
            if (processEntry != null && processEntry.foregroundLike == foregroundLike) {
                filtered.add(processEntry);
            }
        }
        return filtered;
    }

    private ProcessInspector.AppEntry findLiveAppEntry(
            String packageName,
            List<ProcessInspector.AppEntry> liveAppEntries) {
        if (TextUtils.isEmpty(packageName) || liveAppEntries == null) {
            return null;
        }
        for (ProcessInspector.AppEntry appEntry : liveAppEntries) {
            if (appEntry == null || TextUtils.isEmpty(appEntry.packageSummary)) {
                continue;
            }
            String[] packageNames = appEntry.packageSummary.split("\\s*,\\s*");
            for (String candidate : packageNames) {
                if (packageName.equals(candidate)) {
                    return appEntry;
                }
            }
        }
        return null;
    }

    private TextView addSection(LinearLayout parent, String title) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, dp(16), 0, dp(6));
        parent.addView(titleView);

        TextView contentView = new TextView(this);
        contentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        contentView.setBackgroundColor(android.graphics.Color.argb(12, 0, 0, 0));
        contentView.setPadding(dp(16), dp(12), dp(16), dp(12));
        contentView.setTypeface(android.graphics.Typeface.MONOSPACE);
        parent.addView(contentView, matchParentLayoutParams());
        return contentView;
    }

    private String formatPercent(double value) {
        return String.format(java.util.Locale.US, "%.1f%%", Math.max(0d, value));
    }

    private LinearLayout.LayoutParams matchParentLayoutParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()));
    }
}
