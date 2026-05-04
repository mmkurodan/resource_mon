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
    private TextView processListView;
    private TextView recentAppsView;
    private Button usageAccessButton;

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
                startActivity(processInspector.buildUsageAccessIntent());
            }
        });
        LinearLayout.LayoutParams buttonParams = matchParentLayoutParams();
        buttonParams.topMargin = dp(8);
        container.addView(usageAccessButton, buttonParams);

        processListView = addSection(container, "Running processes");
        recentAppsView = addSection(container, "Recent apps");
        return scrollView;
    }

    private void refreshContent() {
        MetricsSampler.MetricsSnapshot latestSample = monitorStore.getLatestSample();
        summaryView.setText(buildSummaryText(latestSample));
        memoryDetailView.setText(buildMemoryDetailText(latestSample));

        ProcessInspector.ProcessReport report = processInspector.collect();
        processNoteView.setText(report.note);
        processListView.setText(buildProcessListText(report.processEntries));
        recentAppsView.setText(buildRecentAppsText(report.recentApps));
        usageAccessButton.setEnabled(!report.usageAccessGranted);
    }

    private String buildSummaryText(MetricsSampler.MetricsSnapshot latestSample) {
        if (latestSample == null) {
            return "No recorded samples yet.";
        }
        return "Background recording: " + (monitorStore.isMonitoringEnabled() ? "ON" : "OFF")
                + "\nLast sample: " + DateUtils.getRelativeTimeSpanString(
                        latestSample.timestampMillis,
                        System.currentTimeMillis(),
                        DateUtils.SECOND_IN_MILLIS)
                + "\nCPU: " + formatPercent(latestSample.cpuUsagePercent)
                + " | RAM: " + formatPercent(latestSample.ramUsagePercent());
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

    private String buildProcessListText(List<ProcessInspector.ProcessEntry> processEntries) {
        if (processEntries == null || processEntries.isEmpty()) {
            return "No process information was exposed by Android.";
        }
        List<String> lines = new ArrayList<>();
        for (ProcessInspector.ProcessEntry processEntry : processEntries) {
            lines.add(processEntry.label
                    + " (pid " + processEntry.pid + ", importance " + processEntry.importance + ")"
                    + "\nCPU: " + processEntry.cpuText
                    + " | Memory: " + processEntry.memoryText
                    + "\nAddress space: " + processEntry.addressSpaceText
                    + "\nProcess: " + processEntry.processName
                    + "\nPackages: " + processEntry.packageSummary);
        }
        return TextUtils.join("\n\n", lines);
    }

    private String buildRecentAppsText(List<ProcessInspector.RecentAppEntry> recentApps) {
        if (recentApps == null || recentApps.isEmpty()) {
            return "Grant usage access to see recently used apps here.";
        }
        List<String> lines = new ArrayList<>();
        for (ProcessInspector.RecentAppEntry recentApp : recentApps) {
            lines.add(recentApp.label
                    + "\nPackage: " + recentApp.packageName
                    + "\nLast used: " + DateUtils.getRelativeTimeSpanString(
                            recentApp.lastTimeUsed,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS)
                    + " | Foreground time: "
                    + DateUtils.formatElapsedTime(recentApp.totalForegroundTimeMs / 1000L));
        }
        return TextUtils.join("\n\n", lines);
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
