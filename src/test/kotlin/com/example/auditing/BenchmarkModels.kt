package com.example.auditing

import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class AuditLog(
    val userId: String,
    val userIp: String,
    val time: Instant,
    val payload: Map<String, Any>
)

enum class AppenderType(val displayName: String) {
    STANDARD_ASYNC("Standard AsyncAppender"),
    DISRUPTOR_ASYNC("Disruptor AsyncAppender")
}

data class SystemInfoData(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val cpuName: String,
    val cpuPhysicalCores: Int,
    val cpuLogicalCores: Int,
    val cpuMaxFreqGHz: Double,
    val totalMemoryGB: Double,
    val memoryType: String,
    val memorySpeedMHz: Long,
    val disks: List<DiskInfo>,
    val jvmName: String,
    val jvmVersion: String,
    val jvmVendor: String,
    val garbageCollectors: List<String>
)

data class DiskInfo(
    val name: String,
    val model: String,
    val sizeGB: Double,
    val isSSD: Boolean
)

data class BenchmarkConfig(
    val threadCount: Int,
    val messagesPerThread: Int,
    val heapMonitorIntervalMs: Long = 2000
) {
    val totalMessages: Int get() = threadCount * messagesPerThread
}

data class BenchmarkResult(
    val messagePrefix: String,
    val wallClockTimeNanos: Long,
    val serializationLatency: LatencyPercentiles,
    val loggingLatency: LatencyPercentiles,
    val totalLatency: LatencyPercentiles
)

data class LatencyPercentiles(
    val p25Nanos: Long,
    val p50Nanos: Long,
    val p75Nanos: Long,
    val p99Nanos: Long,
    val p99_5Nanos: Long
) {
    val p25Micros: Double get() = p25Nanos / 1_000.0
    val p50Micros: Double get() = p50Nanos / 1_000.0
    val p75Micros: Double get() = p75Nanos / 1_000.0
    val p99Micros: Double get() = p99Nanos / 1_000.0
    val p99_5Micros: Double get() = p99_5Nanos / 1_000.0
}

class LatencyCollector(private val expectedSize: Int) {
    private val latencies = LongArray(expectedSize)
    private val index = AtomicInteger(0)

    fun record(latencyNanos: Long) {
        val i = index.getAndIncrement()
        if (i < latencies.size) {
            latencies[i] = latencyNanos
        }
    }

    fun calculatePercentiles(): LatencyPercentiles {
        val count = minOf(index.get(), latencies.size)
        val sorted = latencies.copyOf(count).apply { sort() }

        return LatencyPercentiles(
            p25Nanos = percentile(sorted, 25.0),
            p50Nanos = percentile(sorted, 50.0),
            p75Nanos = percentile(sorted, 75.0),
            p99Nanos = percentile(sorted, 99.0),
            p99_5Nanos = percentile(sorted, 99.5)
        )
    }

    private fun percentile(sortedArray: LongArray, percentile: Double): Long {
        if (sortedArray.isEmpty()) return 0L
        val index = (percentile / 100.0 * (sortedArray.size - 1)).toInt()
        return sortedArray[index.coerceIn(0, sortedArray.lastIndex)]
    }
}

class DualLatencyCollector(private val expectedSize: Int) {
    private val serializationLatencies = LongArray(expectedSize)
    private val loggingLatencies = LongArray(expectedSize)
    private val totalLatencies = LongArray(expectedSize)
    private val index = AtomicInteger(0)

    fun record(serializationNanos: Long, loggingNanos: Long) {
        val i = index.getAndIncrement()
        if (i < serializationLatencies.size) {
            serializationLatencies[i] = serializationNanos
            loggingLatencies[i] = loggingNanos
            totalLatencies[i] = serializationNanos + loggingNanos
        }
    }

    fun calculateSerializationPercentiles(): LatencyPercentiles = calculatePercentiles(serializationLatencies)
    fun calculateLoggingPercentiles(): LatencyPercentiles = calculatePercentiles(loggingLatencies)
    fun calculateTotalPercentiles(): LatencyPercentiles = calculatePercentiles(totalLatencies)

    private fun calculatePercentiles(latencies: LongArray): LatencyPercentiles {
        val count = minOf(index.get(), latencies.size)
        val sorted = latencies.copyOf(count).apply { sort() }

        return LatencyPercentiles(
            p25Nanos = percentile(sorted, 25.0),
            p50Nanos = percentile(sorted, 50.0),
            p75Nanos = percentile(sorted, 75.0),
            p99Nanos = percentile(sorted, 99.0),
            p99_5Nanos = percentile(sorted, 99.5)
        )
    }

    private fun percentile(sortedArray: LongArray, percentile: Double): Long {
        if (sortedArray.isEmpty()) return 0L
        val index = (percentile / 100.0 * (sortedArray.size - 1)).toInt()
        return sortedArray[index.coerceIn(0, sortedArray.lastIndex)]
    }
}

data class AppenderConfig(
    val appenderType: AppenderType,
    val queueSize: Int?,
    val discardingThreshold: Int?,
    val neverBlock: Boolean?,
    val ringBufferSize: Int?,
    val producerType: String?,
    val waitStrategy: String?,
    val immediateFlush: Boolean?,
    val fsyncEnabled: Boolean?,
    val file: String?,
    val maxFileSize: Long?,
    val maxHistory: Int?,
    val totalSizeCap: Long?
)

data class HeapStats(val used: Long, val committed: Long, val max: Long)

data class HeapMonitorResult(val initial: HeapStats, val peak: HeapStats, val final: HeapStats)

class HeapMonitor {
    val isRunning = AtomicBoolean(true)
    val peakUsed = AtomicLong(0)
    var scheduler: ScheduledExecutorService? = null
    var initialStats: HeapStats = HeapStats(0, 0, 0)
}
