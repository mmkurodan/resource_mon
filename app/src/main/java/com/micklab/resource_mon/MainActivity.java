package com.micklab.resource_mon;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int MAX_POINTS = 60;

    private LineChart cpuChart;
    private LineChart ramChart;
    private LineChart romChart;
    private LineChart netChart;

    private MetricsSampler sampler;
    private final MetricsSampler.Listener listener = new MetricsSampler.Listener() {
        @Override
        public void onSample(MetricsSampler.MetricsSnapshot snapshot) {
            addEntry(cpuChart, snapshot.cpuAverageHz, snapshot.cpuMaxHz);
            addEntry(ramChart, snapshot.ramUsedMb, snapshot.ramTotalMb);
            addEntry(romChart, snapshot.storageFreeMb, snapshot.storageTotalMb);
            addEntry(netChart, snapshot.networkBytesPerSec, snapshot.networkMaxBytesPerSec);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        setupChart(cpuChart, "CPU Avg Hz", "CPU Max Hz", Color.RED);
        setupChart(ramChart, "RAM Used (MB)", "RAM Max (MB)", Color.BLUE);
        setupChart(romChart, "ROM Free (MB)", "ROM Max (MB)", Color.MAGENTA);
        setupChart(netChart, "Network (B/s)", "Network Max (B/s)", Color.GREEN);

        sampler = new MetricsSampler(this, 1000L);
        sampler.addListener(listener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sampler.start();
    }

    @Override
    protected void onPause() {
        sampler.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sampler.removeListener(listener);
        sampler.stop();
        super.onDestroy();
    }

    private ScrollView buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        container.setPadding(padding, padding, padding, padding);
        scrollView.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView titleView = new TextView(this);
        titleView.setText("Android Resource Monitor");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText("CPU, RAM, ROM, Network usage (live) - legend: Current / Max");
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        subtitleView.setPadding(0, dp(8), 0, dp(12));
        container.addView(subtitleView);

        cpuChart = addChartSection(container, "CPU");
        ramChart = addChartSection(container, "RAM");
        romChart = addChartSection(container, "ROM");
        netChart = addChartSection(container, "Network");

        return scrollView;
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

    private void setupChart(LineChart chart, String currentLabel, String maxLabel, int color) {
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

    private void addEntry(LineChart chart, long currentValue, long maxValue) {
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
        float current = Math.max(0f, (float) currentValue);
        float max = Math.max((float) maxValue, current);
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

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()));
    }
}
