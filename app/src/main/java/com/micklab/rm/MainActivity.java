package com.micklab.rm;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int MAX_POINTS = 60;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 2001;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshFromStore();
            uiHandler.postDelayed(this, 1000L);
        }
    };

    private LineChart cpuChart;
    private LineChart ramChart;
    private LineChart romChart;
    private LineChart netChart;

    private TextView statusView;
    private TextView miniCpuValueView;
    private TextView miniCpuMetaView;
    private TextView miniRamValueView;
    private TextView miniRamMetaView;
    private TextView recordingSummaryView;
    private Button startButton;
    private Button stopButton;

    private MonitorStore monitorStore;
    private long lastRenderedTimestamp = Long.MIN_VALUE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        monitorStore = MonitorStore.get(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        setContentView(buildContentView());
        resetCharts();
        requestNotificationPermissionIfNeeded();

        if (!monitorStore.isMonitoringEnabled() && monitorStore.getLatestSample() == null) {
            startMonitoring();
        } else {
            updateServiceState();
            renderHistory();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderHistory();
        refreshFromStore();
        uiHandler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private ScrollView buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        final int basePadding = dp(16);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(basePadding, basePadding, basePadding, basePadding);
        scrollView.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        scrollView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int topInset = insets.getSystemWindowInsetTop();
                int bottomInset = insets.getSystemWindowInsetBottom();
                int leftInset = insets.getSystemWindowInsetLeft();
                int rightInset = insets.getSystemWindowInsetRight();
                container.setPadding(
                        basePadding + leftInset,
                        basePadding + topInset,
                        basePadding + rightInset,
                        basePadding + bottomInset);
                return insets;
            }
        });
        scrollView.requestApplyInsets();

        TextView titleView = new TextView(this);
        titleView.setText("Android Resource Monitor");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText("Foreground notification + background recording + live resource history");
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        subtitleView.setPadding(0, dp(8), 0, dp(12));
        container.addView(subtitleView);

        statusView = createCardTextView(false);
        container.addView(statusView, matchParentLayoutParams());

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(12), 0, 0);
        container.addView(actionRow, matchParentLayoutParams());

        startButton = new Button(this);
        startButton.setText("Start background recording");
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startMonitoring();
            }
        });
        actionRow.addView(startButton, weightedLayoutParams());

        stopButton = new Button(this);
        stopButton.setText("Stop");
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopMonitoring();
            }
        });
        LinearLayout.LayoutParams stopParams = weightedLayoutParams();
        stopParams.leftMargin = dp(8);
        actionRow.addView(stopButton, stopParams);

        Button detailButton = new Button(this);
        detailButton.setText("Details / Processes");
        detailButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, ProcessDetailsActivity.class));
            }
        });
        LinearLayout.LayoutParams detailParams = weightedLayoutParams();
        detailParams.leftMargin = dp(8);
        actionRow.addView(detailButton, detailParams);

        LinearLayout miniMonitor = new LinearLayout(this);
        miniMonitor.setOrientation(LinearLayout.HORIZONTAL);
        miniMonitor.setPadding(0, dp(12), 0, 0);
        container.addView(miniMonitor, matchParentLayoutParams());

        miniMonitor.addView(buildMiniMonitorCard(
                "CPU",
                new TextView[]{miniCpuValueView = new TextView(this), miniCpuMetaView = new TextView(this)}),
                weightedLayoutParams());

        LinearLayout.LayoutParams secondCardParams = weightedLayoutParams();
        secondCardParams.leftMargin = dp(8);
        miniMonitor.addView(buildMiniMonitorCard(
                "RAM",
                new TextView[]{miniRamValueView = new TextView(this), miniRamMetaView = new TextView(this)}),
                secondCardParams);

        recordingSummaryView = createCardTextView(true);
        recordingSummaryView.setPadding(dp(16), dp(12), dp(16), dp(12));
        container.addView(recordingSummaryView, matchParentLayoutParams());

        cpuChart = addChartSection(container, "CPU frequency");
        ramChart = addChartSection(container, "RAM");
        romChart = addChartSection(container, "Storage");
        netChart = addChartSection(container, "Network");

        return scrollView;
    }

    private void startMonitoring() {
        monitorStore.setMonitoringEnabled(true);
        MonitorForegroundService.start(this);
        updateServiceState();
    }

    private void stopMonitoring() {
        monitorStore.setMonitoringEnabled(false);
        MonitorForegroundService.stop(this);
        updateServiceState();
    }

    private void refreshFromStore() {
        updateServiceState();
        MetricsSampler.MetricsSnapshot latestSample = monitorStore.getLatestSample();
        if (latestSample == null) {
            return;
        }
        if (latestSample.timestampMillis > lastRenderedTimestamp) {
            renderSnapshot(latestSample);
        } else {
            updateMiniMonitor(latestSample);
            updateRecordingSummary(latestSample);
        }
    }

    private void renderHistory() {
        List<MetricsSampler.MetricsSnapshot> history = monitorStore.getRecentSamples();
        resetCharts();
        lastRenderedTimestamp = Long.MIN_VALUE;
        for (MetricsSampler.MetricsSnapshot snapshot : history) {
            renderSnapshot(snapshot);
        }
        if (history.isEmpty()) {
            updateMiniMonitor(null);
            updateRecordingSummary(null);
        }
    }

    private void updateServiceState() {
        boolean enabled = monitorStore.isMonitoringEnabled();
        MetricsSampler.MetricsSnapshot latestSample = monitorStore.getLatestSample();

        StringBuilder builder = new StringBuilder();
        builder.append("Background recording: ").append(enabled ? "ON" : "OFF");
        if (latestSample != null) {
            builder.append("\nLast sample: ")
                    .append(DateUtils.getRelativeTimeSpanString(
                            latestSample.timestampMillis,
                            System.currentTimeMillis(),
                            DateUtils.SECOND_IN_MILLIS));
            builder.append("\nNotification summary: CPU ")
                    .append(formatPercent(latestSample.cpuUsagePercent))
                    .append(" | RAM ")
                    .append(formatPercent(latestSample.ramUsagePercent()));
        } else {
            builder.append("\nWaiting for the first recorded sample.");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            builder.append("\nNotification permission is not granted yet, so the foreground notice may stay hidden.");
        }
        statusView.setText(builder.toString());

        startButton.setEnabled(!enabled);
        stopButton.setEnabled(enabled);
    }

    private View buildMiniMonitorCard(String title, TextView[] textViews) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.argb(18, 33, 150, 243));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(titleView);

        TextView valueView = textViews[0];
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setPadding(0, dp(4), 0, 0);
        card.addView(valueView);

        TextView metaView = textViews[1];
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        metaView.setPadding(0, dp(4), 0, 0);
        card.addView(metaView);
        return card;
    }

    private TextView createCardTextView(boolean addTopMargin) {
        TextView textView = new TextView(this);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        textView.setBackgroundColor(Color.argb(14, 0, 0, 0));
        textView.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (addTopMargin) {
            LinearLayout.LayoutParams layoutParams = matchParentLayoutParams();
            layoutParams.topMargin = dp(12);
            textView.setLayoutParams(layoutParams);
        }
        return textView;
    }

    private LineChart addChartSection(LinearLayout parent, String title) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, dp(14), 0, dp(6));
        parent.addView(titleView);

        LineChart chart = new LineChart(this);
        chart.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(200)));
        parent.addView(chart);
        return chart;
    }

    private void resetCharts() {
        setupChart(cpuChart, "CPU Avg MHz", "CPU Max MHz", Color.RED);
        setupChart(ramChart, "RAM Used (MB)", "RAM Max (MB)", Color.BLUE);
        setupChart(romChart, "Storage Used (MB)", "Storage Max (MB)", Color.MAGENTA);
        setupChart(netChart, "Network (B/s)", "Network Max (B/s)", Color.GREEN);
    }

    private void setupChart(LineChart chart, String currentLabel, String maxLabel, int color) {
        if (chart == null) {
            return;
        }
        chart.getDescription().setEnabled(false);
        chart.setTouchEnabled(true);
        chart.setDrawGridBackground(false);
        chart.setPinchZoom(true);
        chart.getAxisRight().setEnabled(false);
        chart.getXAxis().setEnabled(false);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getLegend().setEnabled(true);

        LineDataSet currentSet = new LineDataSet(new ArrayList<Entry>(), currentLabel);
        currentSet.setColor(color);
        currentSet.setDrawCircles(false);
        currentSet.setDrawValues(false);
        currentSet.setLineWidth(2f);

        LineDataSet maxSet = new LineDataSet(new ArrayList<Entry>(), maxLabel);
        maxSet.setColor(Color.DKGRAY);
        maxSet.enableDashedLine(10f, 8f, 0f);
        maxSet.setDrawCircles(false);
        maxSet.setDrawValues(false);
        maxSet.setLineWidth(1.5f);

        chart.setData(new LineData(currentSet, maxSet));
        chart.invalidate();
    }

    private void renderSnapshot(MetricsSampler.MetricsSnapshot snapshot) {
        updateMiniMonitor(snapshot);
        updateRecordingSummary(snapshot);
        addEntry(cpuChart, snapshot.cpuAverageMhz, snapshot.cpuMaxMhz);
        addEntry(ramChart, snapshot.ramUsedMb, snapshot.ramTotalMb);
        addEntry(romChart, snapshot.storageTotalMb - snapshot.storageFreeMb, snapshot.storageTotalMb);
        addEntry(netChart, snapshot.networkBytesPerSec, snapshot.networkMaxBytesPerSec);
        lastRenderedTimestamp = snapshot.timestampMillis;
    }

    private void updateMiniMonitor(MetricsSampler.MetricsSnapshot snapshot) {
        if (snapshot == null) {
            miniCpuValueView.setText("--");
            miniCpuMetaView.setText("Waiting for data");
            miniRamValueView.setText("--");
            miniRamMetaView.setText("Waiting for data");
            return;
        }

        miniCpuValueView.setText(formatPercent(snapshot.cpuUsagePercent));
        miniCpuMetaView.setText("Avg "
                + String.format(Locale.US, "%.0f MHz", snapshot.cpuAverageMhz)
                + " / Max "
                + String.format(Locale.US, "%.0f MHz", snapshot.cpuMaxMhz));

        miniRamValueView.setText(formatPercent(snapshot.ramUsagePercent()));
        miniRamMetaView.setText(Formatter.formatShortFileSize(this, snapshot.memoryDetails.usedBytes)
                + " used | Cached "
                + Formatter.formatShortFileSize(this, snapshot.memoryDetails.cacheLikeBytes()));
    }

    private void updateRecordingSummary(MetricsSampler.MetricsSnapshot snapshot) {
        if (snapshot == null) {
            recordingSummaryView.setText("Recorded samples will appear here once background monitoring starts.");
            return;
        }

        List<String> parts = new ArrayList<>();
        parts.add("Available " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.availableBytes));
        parts.add("Free " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.freeBytes));
        parts.add("Buffers " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.buffersBytes));
        parts.add("Cached " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.cachedBytes));
        if (snapshot.memoryDetails.swapTotalBytes > 0L) {
            parts.add("Swap " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.swapUsedBytes())
                    + " / " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.swapTotalBytes));
        }
        parts.add("Updated "
                + DateUtils.getRelativeTimeSpanString(
                        snapshot.timestampMillis,
                        System.currentTimeMillis(),
                        DateUtils.SECOND_IN_MILLIS));
        recordingSummaryView.setText(TextUtils.join("  |  ", parts));
    }

    private void addEntry(LineChart chart, double currentValue, double maxValue) {
        LineData data = chart.getData();
        if (data == null) {
            return;
        }
        ILineDataSet currentDataSet = data.getDataSetByIndex(0);
        ILineDataSet maxDataSet = data.getDataSetByIndex(1);
        if (!(currentDataSet instanceof LineDataSet) || !(maxDataSet instanceof LineDataSet)) {
            return;
        }
        LineDataSet currentSet = (LineDataSet) currentDataSet;
        float x = currentSet.getEntryCount();
        float current = (float) Math.max(0d, currentValue);
        float max = (float) Math.max(currentValue, maxValue);
        if (max <= 0f) {
            max = 1f;
        }

        data.addEntry(new Entry(x, current), 0);
        data.addEntry(new Entry(x, max), 1);
        data.notifyDataChanged();
        chart.notifyDataSetChanged();
        chart.getAxisLeft().setAxisMaximum(max);
        chart.setVisibleXRangeMaximum(MAX_POINTS);
        chart.moveViewToX(currentSet.getEntryCount());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            updateServiceState();
        }
    }

    private String formatPercent(double value) {
        return String.format(Locale.US, "%.1f%%", Math.max(0d, value));
    }

    private LinearLayout.LayoutParams matchParentLayoutParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedLayoutParams() {
        return new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()));
    }
}
