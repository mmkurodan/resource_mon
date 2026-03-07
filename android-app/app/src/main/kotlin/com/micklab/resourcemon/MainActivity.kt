package com.micklab.resourcemon

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class MainActivity : AppCompatActivity() {

    private lateinit var cpuChart: LineChart
    private lateinit var ramChart: LineChart
    private lateinit var romChart: LineChart
    private lateinit var netChart: LineChart

    private lateinit var sampler: MetricsSampler
    private val maxPoints = 60

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cpuChart = findViewById(R.id.cpuChart)
        ramChart = findViewById(R.id.ramChart)
        romChart = findViewById(R.id.romChart)
        netChart = findViewById(R.id.netChart)

        setupChart(cpuChart, "CPU %", Color.RED)
        setupChart(ramChart, "RAM MB", Color.BLUE)
        setupChart(romChart, "Free ROM MB", Color.MAGENTA)
        setupChart(netChart, "Net B/s", Color.GREEN)

        sampler = MetricsSampler(this, 1000L)
        sampler.addListener { s ->
            // MetricsSampler posts to main thread, but ensure UI updates are on UI thread
            runOnUiThread {
                addEntry(cpuChart, s.cpuPercent)
                addEntry(ramChart, s.ramUsedMb.toFloat())
                addEntry(romChart, s.storageFreeMb.toFloat())
                val net = s.rxBytes.toFloat() + s.txBytes.toFloat()
                addEntry(netChart, net)
            }
        }
        sampler.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        sampler.stop()
    }

    private fun setupChart(chart: LineChart, label: String, color: Int) {
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(true)
        val set = LineDataSet(mutableListOf(), label)
        set.color = color
        set.setDrawCircles(false)
        set.setDrawValues(false)
        set.lineWidth = 2f
        val data = LineData(set)
        chart.data = data
        chart.axisRight.isEnabled = false
        chart.xAxis.isEnabled = false
        chart.legend.isEnabled = false
        chart.invalidate()
    }

    private fun addEntry(chart: LineChart, value: Float) {
        val data = chart.data ?: return
        val set = data.getDataSetByIndex(0) as? LineDataSet ?: return
        val entry = Entry(set.entryCount.toFloat(), value)
        data.addEntry(entry, 0)
        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(maxPoints.toFloat())
        chart.moveViewToX(data.entryCount.toFloat())
    }
}
