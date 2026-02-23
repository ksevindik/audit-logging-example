package com.example.auditing

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import com.example.auditing.logging.FsyncRollingFileAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.logstash.logback.appender.LoggingEventAsyncDisruptorAppender
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.ReflectionUtils
import oshi.SystemInfo
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@SpringBootTest
abstract class BaseAsyncLoggingBenchmarkTest {

    companion object {
        val LOG_DIR: Path = Files.createTempDirectory("logging-benchmark")

        init {
            System.setProperty("LOG_PATH", LOG_DIR.toString())
            println("Set LOG_PATH to: $LOG_DIR")
        }
    }

    private val logger: Logger = LoggerFactory.getLogger(BaseAsyncLoggingBenchmarkTest::class.java)

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val logFile: Path
        get() = LOG_DIR.resolve("spring.log")

    @BeforeEach
    fun setup() {
        if (Files.exists(logFile)) {
            Files.writeString(logFile, "")
        }
    }

    fun runTest() {
        val systemInfo = collectSystemInfo()
        printSystemInfo(systemInfo)

        val config = BenchmarkConfig(
            threadCount = 200,
            messagesPerThread = 50_000,
            heapMonitorIntervalMs = 2000
        )

        val heapMonitor = startHeapMonitor(config.heapMonitorIntervalMs)
        val benchmarkResult = runBenchmark(config)
        val heapResult = stopHeapMonitor(heapMonitor)

        flushAsyncAppender()

        val appenderConfig = getAppenderConfiguration()
        val isFileAppender = appenderConfig?.file != null

        val foundMessages = if (isFileAppender) {
            countMessagesInLogFiles(benchmarkResult.messagePrefix)
        } else {
            println("\n=== Message Validation ===")
            println("Skipped: Console appender does not persist messages to disk")
            println("==========================\n")
            -1
        }

        printResults(config, benchmarkResult, heapResult, appenderConfig, foundMessages)

        if (isFileAppender) {
            assertEquals(
                config.totalMessages,
                foundMessages,
                "Expected ${config.totalMessages} messages but found $foundMessages - MESSAGE LOSS DETECTED!"
            )
        }
    }

    private fun runBenchmark(config: BenchmarkConfig): BenchmarkResult {
        val messagePrefix = "B-${UUID.randomUUID().toString().take(8)}"
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val startLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(config.threadCount)
        val latencyCollector = DualLatencyCollector(config.totalMessages)

        submitLoggingTasks(executor, config, messagePrefix, startLatch, completionLatch, latencyCollector)

        val wallClockTime = measureWallClockTime(startLatch, completionLatch)

        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        return BenchmarkResult(
            messagePrefix = messagePrefix,
            wallClockTimeNanos = wallClockTime,
            serializationLatency = latencyCollector.calculateSerializationPercentiles(),
            loggingLatency = latencyCollector.calculateLoggingPercentiles(),
            totalLatency = latencyCollector.calculateTotalPercentiles()
        )
    }

    private fun submitLoggingTasks(
        executor: java.util.concurrent.ExecutorService,
        config: BenchmarkConfig,
        messagePrefix: String,
        startLatch: CountDownLatch,
        completionLatch: CountDownLatch,
        latencyCollector: DualLatencyCollector
    ) {
        for (threadId in 1..config.threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    val threadLogger = LoggerFactory.getLogger("Thread-$threadId")
                    val userId = "user-$threadId"
                    val userIp = "192.168.1.$threadId"

                    for (i in 1..config.messagesPerThread) {
                        val auditLog = AuditLog(
                            userId = userId,
                            userIp = userIp,
                            time = Instant.now(),
                            payload = mapOf(
                                "messagePrefix" to messagePrefix,
                                "threadId" to threadId,
                                "messageNum" to i,
                                "action" to "benchmark"
                            )
                        )

                        val serializationStart = System.nanoTime()
                        val json = objectMapper.writeValueAsString(auditLog)
                        val serializationTime = System.nanoTime() - serializationStart

                        val loggingStart = System.nanoTime()
                        threadLogger.warn(json)
                        val loggingTime = System.nanoTime() - loggingStart

                        latencyCollector.record(serializationTime, loggingTime)
                    }
                } finally {
                    completionLatch.countDown()
                }
            }
        }
    }

    private fun measureWallClockTime(startLatch: CountDownLatch, completionLatch: CountDownLatch): Long {
        val wallClockStart = System.nanoTime()
        startLatch.countDown()
        completionLatch.await()
        return System.nanoTime() - wallClockStart
    }

    private fun countMessagesInLogFiles(messagePrefix: String): Int {
        val allLogFiles = Files.list(LOG_DIR)
            .filter { it.fileName.toString().startsWith("spring.log") }
            .toList()

        printLogFileSummary(allLogFiles)

        return allLogFiles.sumOf { file ->
            Files.readString(file).lines().count { it.contains(messagePrefix) }
        }
    }

    private fun printLogFileSummary(logFiles: List<Path>) {
        println("\n=== Log Files Created ===")
        logFiles.forEach { file ->
            println("${file.fileName}: ${formatBytes(Files.size(file))}")
        }
        println("=========================\n")
    }

    // region Appender Configuration

    private fun findActiveAsyncAppender(): Appender<ILoggingEvent>? {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)

        return rootLogger.getAppender("ASYNC")
            ?: rootLogger.getAppender("ASYNC_DISRUPTOR")
    }

    private fun getAppenderConfiguration(): AppenderConfig? {
        val asyncAppender = findActiveAsyncAppender() ?: return null

        return when (asyncAppender) {
            is AsyncAppender -> getStandardAsyncConfig(asyncAppender)
            is LoggingEventAsyncDisruptorAppender -> getDisruptorAsyncConfig(asyncAppender)
            else -> null
        }
    }

    private fun getStandardAsyncConfig(appender: AsyncAppender): AppenderConfig {
        val wrappedAppender = findWrappedAppender(appender)
        val rollingFileAppender = wrappedAppender as? RollingFileAppender<ILoggingEvent>
        val fsyncAppender = wrappedAppender as? FsyncRollingFileAppender<ILoggingEvent>
        val rollingPolicy = rollingFileAppender?.rollingPolicy as? SizeAndTimeBasedRollingPolicy<ILoggingEvent>
        val outputStreamAppender = wrappedAppender as? OutputStreamAppender<ILoggingEvent>

        return AppenderConfig(
            appenderType = AppenderType.STANDARD_ASYNC,
            queueSize = appender.queueSize,
            discardingThreshold = appender.discardingThreshold,
            neverBlock = appender.isNeverBlock,
            ringBufferSize = null,
            producerType = null,
            waitStrategy = null,
            immediateFlush = outputStreamAppender?.isImmediateFlush,
            fsyncEnabled = fsyncAppender?.fsyncEnabled,
            file = rollingFileAppender?.file,
            maxFileSize = getMaxFileSize(rollingPolicy),
            maxHistory = rollingPolicy?.maxHistory,
            totalSizeCap = getTotalSizeCap(rollingPolicy)
        )
    }

    private fun getDisruptorAsyncConfig(appender: LoggingEventAsyncDisruptorAppender): AppenderConfig {
        val wrappedAppender = findWrappedAppender(appender)
        val rollingFileAppender = wrappedAppender as? RollingFileAppender<ILoggingEvent>
        val fsyncAppender = wrappedAppender as? FsyncRollingFileAppender<ILoggingEvent>
        val rollingPolicy = rollingFileAppender?.rollingPolicy as? SizeAndTimeBasedRollingPolicy<ILoggingEvent>
        val outputStreamAppender = wrappedAppender as? OutputStreamAppender<ILoggingEvent>

        return AppenderConfig(
            appenderType = AppenderType.DISRUPTOR_ASYNC,
            queueSize = null,
            discardingThreshold = null,
            neverBlock = null,
            ringBufferSize = appender.ringBufferSize,
            producerType = appender.producerType?.name,
            waitStrategy = appender.waitStrategy?.javaClass?.simpleName,
            immediateFlush = outputStreamAppender?.isImmediateFlush,
            fsyncEnabled = fsyncAppender?.fsyncEnabled,
            file = rollingFileAppender?.file,
            maxFileSize = getMaxFileSize(rollingPolicy),
            maxHistory = rollingPolicy?.maxHistory,
            totalSizeCap = getTotalSizeCap(rollingPolicy)
        )
    }

    private fun findWrappedAppender(asyncAppender: Appender<ILoggingEvent>): Appender<ILoggingEvent>? {
        return when (asyncAppender) {
            is AsyncAppender -> {
                asyncAppender.getAppender("ROLLING_FILE")
                    ?: asyncAppender.getAppender("CONSOLE")
            }
            is LoggingEventAsyncDisruptorAppender -> {
                asyncAppender.getAppender("ROLLING_FILE")
                    ?: asyncAppender.getAppender("CONSOLE")
            }
            else -> null
        }
    }

    private fun getMaxFileSize(rollingPolicy: SizeAndTimeBasedRollingPolicy<ILoggingEvent>?): Long? {
        if (rollingPolicy == null) return null
        val field = ReflectionUtils.findField(SizeAndTimeBasedRollingPolicy::class.java, "maxFileSize")
        field?.trySetAccessible()
        return (ReflectionUtils.getField(field!!, rollingPolicy) as? FileSize)?.size
    }

    private fun getTotalSizeCap(rollingPolicy: SizeAndTimeBasedRollingPolicy<ILoggingEvent>?): Long? {
        if (rollingPolicy == null) return null
        val field = ReflectionUtils.findField(SizeAndTimeBasedRollingPolicy::class.java, "totalSizeCap")
        field?.trySetAccessible()
        return (ReflectionUtils.getField(field!!, rollingPolicy) as? FileSize)?.size
    }

    // endregion

    // region Flush Appender

    private fun flushAsyncAppender() {
        val asyncAppender = findActiveAsyncAppender()

        when (asyncAppender) {
            is AsyncAppender -> flushStandardAsync(asyncAppender)
            is LoggingEventAsyncDisruptorAppender -> flushDisruptorAsync(asyncAppender)
        }

        Thread.sleep(500)
    }

    private fun flushStandardAsync(appender: AsyncAppender) {
        var retries = 0
        val maxRetries = 100
        while (appender.numberOfElementsInQueue > 0 && retries < maxRetries) {
            Thread.sleep(50)
            retries++
        }
        if (retries >= maxRetries) {
            println("Warning: AsyncAppender queue did not fully drain after ${maxRetries * 50}ms")
        }
    }

    private fun flushDisruptorAsync(appender: LoggingEventAsyncDisruptorAppender) {
        var retries = 0
        val maxRetries = 100
        val method = ReflectionUtils.findMethod(LoggingEventAsyncDisruptorAppender::class.java,"isRingBufferEmpty")
        method!!.trySetAccessible()
        var ringBufferEmpty = ReflectionUtils.invokeMethod(method,appender) as Boolean
        while (!ringBufferEmpty && retries < maxRetries) {
            Thread.sleep(50)
            retries++
            ringBufferEmpty = ReflectionUtils.invokeMethod(method,appender) as Boolean
        }
        if (retries >= maxRetries) {
            println("Warning: DisruptorAppender ring buffer did not fully drain after ${maxRetries * 50}ms")
        }
    }

    // endregion

    // region Print Results

    private fun printResults(
        config: BenchmarkConfig,
        benchmarkResult: BenchmarkResult,
        heapResult: HeapMonitorResult,
        appenderConfig: AppenderConfig?,
        foundMessages: Int
    ) {
        val throughput = config.totalMessages / (benchmarkResult.wallClockTimeNanos / 1_000_000_000.0)
        val isFileAppender = appenderConfig?.file != null

        println(buildString {
            appendLine()
            appendLine("=== Appender Configuration (${appenderConfig?.appenderType?.displayName ?: "Unknown"}) ===")
            appendLine("Target appender:         ${if (isFileAppender) "ROLLING_FILE" else "CONSOLE"}")

            when (appenderConfig?.appenderType) {
                AppenderType.STANDARD_ASYNC -> {
                    appendLine("Queue size:              ${appenderConfig.queueSize ?: "N/A"}")
                    appendLine("Discarding threshold:    ${appenderConfig.discardingThreshold ?: "N/A"}")
                    appendLine("Never block:             ${appenderConfig.neverBlock ?: "N/A"}")
                    appendLine("Immediate flush:         ${appenderConfig.immediateFlush ?: "N/A"}")
                    if (isFileAppender) {
                        appendLine("Fsync enabled:           ${appenderConfig.fsyncEnabled ?: "N/A"}")
                    }
                }
                AppenderType.DISRUPTOR_ASYNC -> {
                    appendLine("Ring buffer size:        ${appenderConfig.ringBufferSize ?: "N/A"}")
                    appendLine("Producer type:           ${appenderConfig.producerType ?: "N/A"}")
                    appendLine("Wait strategy:           ${appenderConfig.waitStrategy ?: "N/A"}")
                    appendLine("Immediate flush:         ${appenderConfig.immediateFlush ?: "N/A"}")
                    if (isFileAppender) {
                        appendLine("Fsync enabled:           ${appenderConfig.fsyncEnabled ?: "N/A"}")
                    }
                }
                null -> appendLine("No appender configuration available")
            }
            if (isFileAppender) {
                appendLine("File:                    ${appenderConfig?.file ?: "N/A"}")
                appendLine("Max file size:           ${appenderConfig?.maxFileSize?.let { formatBytes(it) } ?: "N/A"}")
                appendLine("Max history:             ${appenderConfig?.maxHistory ?: "N/A"}")
                appendLine("Total size cap:          ${appenderConfig?.totalSizeCap?.let { formatBytes(it) } ?: "N/A"}")
            }
            appendLine("=========================================")
            appendLine()
            appendLine("=== Benchmark Results ===")
            appendLine("Threads:                 ${config.threadCount}")
            appendLine("Messages/thread:         ${config.messagesPerThread}")
            appendLine("Total messages:          ${config.totalMessages}")
            appendLine("Wall-clock time:         ${benchmarkResult.wallClockTimeNanos / 1_000_000} ms")
            appendLine("Throughput:              ${String.format("%.2f", throughput)} msgs/sec")
            appendLine()
            appendLine("--- Serialization Latency ---")
            appendLine("  p50:                   ${String.format("%.2f", benchmarkResult.serializationLatency.p50Micros)} µs")
            appendLine("  p99:                   ${String.format("%.2f", benchmarkResult.serializationLatency.p99Micros)} µs")
            appendLine("  p99.5:                 ${String.format("%.2f", benchmarkResult.serializationLatency.p99_5Micros)} µs")
            appendLine()
            appendLine("--- Logging Latency ---")
            appendLine("  p50:                   ${String.format("%.2f", benchmarkResult.loggingLatency.p50Micros)} µs")
            appendLine("  p99:                   ${String.format("%.2f", benchmarkResult.loggingLatency.p99Micros)} µs")
            appendLine("  p99.5:                 ${String.format("%.2f", benchmarkResult.loggingLatency.p99_5Micros)} µs")
            appendLine()
            appendLine("--- Total Latency (Serialization + Logging) ---")
            appendLine("  p25:                   ${String.format("%.2f", benchmarkResult.totalLatency.p25Micros)} µs")
            appendLine("  p50:                   ${String.format("%.2f", benchmarkResult.totalLatency.p50Micros)} µs")
            appendLine("  p75:                   ${String.format("%.2f", benchmarkResult.totalLatency.p75Micros)} µs")
            appendLine("  p99:                   ${String.format("%.2f", benchmarkResult.totalLatency.p99Micros)} µs")
            appendLine("  p99.5:                 ${String.format("%.2f", benchmarkResult.totalLatency.p99_5Micros)} µs")
            appendLine()
            if (isFileAppender) {
                appendLine("Messages logged:         $foundMessages")
                appendLine("Message loss:            ${config.totalMessages - foundMessages}")
            } else {
                appendLine("Messages logged:         N/A (console)")
                appendLine("Message loss:            N/A (console)")
            }
            appendLine("=========================================")
            appendLine()
            appendLine("=== JVM Heap Usage ===")
            appendLine("Max heap:                ${formatBytes(heapResult.final.max)}")
            appendLine("Initial used:            ${formatBytes(heapResult.initial.used)}")
            appendLine("Peak used:               ${formatBytes(heapResult.peak.used)}")
            appendLine("Final used:              ${formatBytes(heapResult.final.used)}")
            appendLine("=======================")
        })
    }

    // endregion

    // region Heap Monitoring

    private fun startHeapMonitor(intervalMs: Long): HeapMonitor {
        println("\n=== Starting Heap Monitor ===")
        val monitor = HeapMonitor()
        val scheduler = Executors.newSingleThreadScheduledExecutor()

        scheduler.scheduleAtFixedRate({
            if (monitor.isRunning.get()) {
                val stats = getHeapStats()
                if (stats.used > monitor.peakUsed.get()) {
                    monitor.peakUsed.set(stats.used)
                }
                println("[Heap Monitor] Used: ${formatBytes(stats.used)} | Committed: ${formatBytes(stats.committed)} | Max: ${formatBytes(stats.max)}")
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS)

        monitor.scheduler = scheduler
        monitor.initialStats = getHeapStats()
        return monitor
    }

    private fun stopHeapMonitor(monitor: HeapMonitor): HeapMonitorResult {
        monitor.isRunning.set(false)
        monitor.scheduler?.shutdown()
        monitor.scheduler?.awaitTermination(5, TimeUnit.SECONDS)

        val finalStats = getHeapStats()
        return HeapMonitorResult(
            initial = monitor.initialStats,
            peak = HeapStats(monitor.peakUsed.get(), finalStats.committed, finalStats.max),
            final = finalStats
        )
    }

    private fun getHeapStats(): HeapStats {
        val heapUsage = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        return HeapStats(heapUsage.used, heapUsage.committed, heapUsage.max)
    }

    // endregion

    // region Utilities

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.2f MB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format("%.2f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }

    // endregion

    // region System Info

    private fun collectSystemInfo(): SystemInfoData {
        val si = SystemInfo()
        val hal = si.hardware
        val processor = hal.processor
        val memory = hal.memory
        val physicalMemories = memory.physicalMemory
        val diskStores = hal.diskStores

        val memoryType = physicalMemories.firstOrNull()?.memoryType ?: "Unknown"
        val memorySpeedMHz = physicalMemories.firstOrNull()?.clockSpeed?.let { it / 1_000_000 } ?: 0L

        val disks = diskStores.map { disk ->
            DiskInfo(
                name = disk.name,
                model = disk.model.trim(),
                sizeGB = disk.size / 1_073_741_824.0,
                isSSD = disk.model.contains("SSD", ignoreCase = true) ||
                        disk.model.contains("NVMe", ignoreCase = true) ||
                        disk.model.contains("Apple", ignoreCase = true)
            )
        }

        val garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans()
            .map { it.name }

        return SystemInfoData(
            osName = si.operatingSystem.family,
            osVersion = si.operatingSystem.versionInfo.version,
            osArch = System.getProperty("os.arch"),
            cpuName = processor.processorIdentifier.name.trim(),
            cpuPhysicalCores = processor.physicalProcessorCount,
            cpuLogicalCores = processor.logicalProcessorCount,
            cpuMaxFreqGHz = processor.maxFreq / 1_000_000_000.0,
            totalMemoryGB = memory.total / 1_073_741_824.0,
            memoryType = memoryType,
            memorySpeedMHz = memorySpeedMHz,
            disks = disks,
            jvmName = System.getProperty("java.vm.name"),
            jvmVersion = System.getProperty("java.version"),
            jvmVendor = System.getProperty("java.vm.vendor"),
            garbageCollectors = garbageCollectors
        )
    }

    private fun printSystemInfo(info: SystemInfoData) {
        println(buildString {
            appendLine()
            appendLine("=== System Environment ===")
            appendLine()
            appendLine("--- Operating System ---")
            appendLine("Name:                    ${info.osName}")
            appendLine("Version:                 ${info.osVersion}")
            appendLine("Arch:                    ${info.osArch}")
            appendLine()
            appendLine("--- CPU ---")
            appendLine("Model:                   ${info.cpuName}")
            appendLine("Physical cores:          ${info.cpuPhysicalCores}")
            appendLine("Logical cores:           ${info.cpuLogicalCores}")
            appendLine("Max frequency:           ${String.format("%.2f GHz", info.cpuMaxFreqGHz)}")
            appendLine()
            appendLine("--- Memory ---")
            appendLine("Total:                   ${String.format("%.2f GB", info.totalMemoryGB)}")
            appendLine("Type:                    ${info.memoryType}")
            appendLine("Speed:                   ${info.memorySpeedMHz} MHz")
            appendLine()
            appendLine("--- Storage ---")
            info.disks.forEach { disk ->
                val diskType = if (disk.isSSD) "SSD" else "HDD"
                appendLine("${disk.model}: ${String.format("%.0f GB", disk.sizeGB)} ($diskType)")
            }
            appendLine()
            appendLine("--- JVM ---")
            appendLine("Name:                    ${info.jvmName}")
            appendLine("Version:                 ${info.jvmVersion}")
            appendLine("Vendor:                  ${info.jvmVendor}")
            appendLine("GC:                      ${info.garbageCollectors.joinToString(", ")}")
            appendLine("==========================")
        })
    }

    // endregion

}
