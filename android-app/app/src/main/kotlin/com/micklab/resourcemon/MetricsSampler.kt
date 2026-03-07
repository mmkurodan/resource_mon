package com.micklab.resourcemon

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.net.TrafficStats
import java.io.RandomAccessFile

data class MetricsSnapshot(
    val timestamp: Long,
    val cpuPercent: Float,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val storageFreeMb: Long,
    val storageTotalMb: Long,
    val rxBytes: Long,
    val txBytes: Long
)

class MetricsSampler(private val context: Context, private val intervalMs: Long = 1000L) {
    private var running = false
    private var thread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(MetricsSnapshot) -> Unit>()
    private var lastRx = TrafficStats.getTotalRxBytes()
    private var lastTx = TrafficStats.getTotalTxBytes()
    private var lastTotal: Long = 0
    private var lastIdle: Long = 0

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                val snapshot = sample()
                mainHandler.post {
                    for (l in listeners) l(snapshot)
                }
                try { Thread.sleep(intervalMs) } catch (e: InterruptedException) { break }
            }
        }
        thread?.start()
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    fun addListener(listener: (MetricsSnapshot) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (MetricsSnapshot) -> Unit) {
        listeners -= listener
    }

    private fun sample(): MetricsSnapshot {
        val cpu = readCpuUsage()
        val (usedRam, totalRam) = readMemory()
        val (freeStorage, totalStorage) = readStorage()
        val (rxDelta, txDelta) = readNetwork()
        return MetricsSnapshot(System.currentTimeMillis(), cpu, usedRam, totalRam, freeStorage, totalStorage, rxDelta, txDelta)
    }

    private fun readCpuUsage(): Float {
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val load = reader.readLine()
            reader.close()
            val toks = load.split(Regex("\\s+"))
            val user = toks.getOrNull(1)?.toLong() ?: 0L
            val nice = toks.getOrNull(2)?.toLong() ?: 0L
            val system = toks.getOrNull(3)?.toLong() ?: 0L
            val idle = toks.getOrNull(4)?.toLong() ?: 0L
            val iowait = toks.getOrNull(5)?.toLong() ?: 0L
            val irq = toks.getOrNull(6)?.toLong() ?: 0L
            val softirq = toks.getOrNull(7)?.toLong() ?: 0L
            val steal = toks.getOrNull(8)?.toLong() ?: 0L
            val total = user + nice + system + idle + iowait + irq + softirq + steal
            val diffTotal = total - lastTotal
            val diffIdle = idle - lastIdle
            val usage = if (lastTotal == 0L || diffTotal <= 0) 0f else ((diffTotal - diffIdle).toFloat() / diffTotal.toFloat()) * 100f
            lastTotal = total
            lastIdle = idle
            return usage
        } catch (e: Exception) {
            return 0f
        }
    }

    private fun readMemory(): Pair<Long, Long> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val total = mi.totalMem / 1024 / 1024
            val avail = mi.availMem / 1024 / 1024
            val used = total - avail
            Pair(used, total)
        } catch (e: Exception) {
            Pair(0L,0L)
        }
    }

    private fun readStorage(): Pair<Long, Long> {
        return try {
            val path = Environment.getDataDirectory().path
            val stat = StatFs(path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            val totalMb = (totalBlocks * blockSize) / 1024 / 1024
            val freeMb = (availableBlocks * blockSize) / 1024 / 1024
            Pair(freeMb, totalMb)
        } catch (e: Exception) {
            Pair(0L,0L)
        }
    }

    private fun readNetwork(): Pair<Long, Long> {
        return try {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val rxDelta = if (lastRx == 0L) 0L else rx - lastRx
            val txDelta = if (lastTx == 0L) 0L else tx - lastTx
            lastRx = rx
            lastTx = tx
            Pair(rxDelta, txDelta)
        } catch (e: Exception) {
            Pair(0L,0L)
        }
    }
}
