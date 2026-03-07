package com.micklab.resource_mon;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final long REFRESH_INTERVAL_MS = 2000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private final DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM);
    private final Runnable refreshTicker = new Runnable() {
        @Override
        public void run() {
            requestRefresh();
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private ExecutorService executorService;
    private DeviceStatsCollector statsCollector;
    private GpuInfoProbeView gpuInfoProbeView;

    private TextView statusView;
    private TextView overviewView;
    private TextView cpuView;
    private TextView gpuView;
    private TextView npuView;
    private TextView memoryView;
    private TextView storageView;
    private TextView networkView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statsCollector = new DeviceStatsCollector(this);
        executorService = Executors.newSingleThreadExecutor();
        setContentView(buildContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        gpuInfoProbeView.onResume();
        gpuInfoProbeView.requestRender();
        requestRefresh();
        mainHandler.postDelayed(refreshTicker, REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(refreshTicker);
        gpuInfoProbeView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(refreshTicker);
        executorService.shutdownNow();
        super.onDestroy();
    }

    private View buildContentView() {
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
        subtitleView.setText(
                "CPU, GPU, NPU, RAM, ROM, and network telemetry with live refresh. "
                        + "GPU/NPU usage is best-effort because Android and OEMs expose different metrics.");
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        subtitleView.setPadding(0, dp(8), 0, dp(12));
        container.addView(subtitleView);

        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh now");
        refreshButton.setAllCaps(false);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestRefresh();
            }
        });
        container.addView(refreshButton);

        statusView = createContentTextView();
        statusView.setPadding(0, dp(12), 0, 0);
        statusView.setText("Initializing...");
        container.addView(statusView);

        overviewView = addSection(container, "Device overview");
        cpuView = addSection(container, "CPU");
        gpuView = addSection(container, "GPU");
        npuView = addSection(container, "NPU / AI accelerator");
        memoryView = addSection(container, "RAM");
        storageView = addSection(container, "ROM / storage");
        networkView = addSection(container, "Network");

        gpuInfoProbeView = new GpuInfoProbeView(this, new GpuInfoProbeView.Listener() {
            @Override
            public void onGpuInfoReady(String vendor, String renderer, String version) {
                statsCollector.updateGpuInfo(vendor, renderer, version);
                requestRefresh();
            }
        });
        gpuInfoProbeView.setLayoutParams(new LinearLayout.LayoutParams(1, 1));
        gpuInfoProbeView.setAlpha(0.01f);
        container.addView(gpuInfoProbeView);

        return scrollView;
    }

    private TextView addSection(LinearLayout parent, String title) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, dp(18), 0, dp(6));
        parent.addView(titleView);

        TextView contentView = createContentTextView();
        parent.addView(contentView);
        return contentView;
    }

    private TextView createContentTextView() {
        TextView textView = new TextView(this);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextIsSelectable(true);
        textView.setLineSpacing(0f, 1.1f);
        return textView;
    }

    private void requestRefresh() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        statusView.setText("Refreshing metrics...");
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final DeviceStatsCollector.Snapshot snapshot = statsCollector.collect();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            renderSnapshot(snapshot);
                        }
                    });
                } finally {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshInFlight.set(false);
                        }
                    });
                }
            }
        });
    }

    private void renderSnapshot(DeviceStatsCollector.Snapshot snapshot) {
        statusView.setText(
                "Last updated: " + timeFormat.format(new Date(snapshot.collectedAtMillis))
                        + "  |  auto refresh: 2s");
        overviewView.setText(snapshot.overview);
        cpuView.setText(snapshot.cpu);
        gpuView.setText(snapshot.gpu);
        npuView.setText(snapshot.npu);
        memoryView.setText(snapshot.memory);
        storageView.setText(snapshot.storage);
        networkView.setText(snapshot.network);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()));
    }
}
