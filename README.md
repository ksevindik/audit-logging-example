# Audit Logging Benchmark

A Spring Boot project for benchmarking audit logging performance with various async appender configurations. This project measures the impact of JSON serialization and logging on latency, and validates that no log messages are lost under high-throughput conditions.

## Features

- **Custom FsyncRollingFileAppender**: Guarantees log data is persisted to physical disk via `fsync()` system call
- **Multiple Async Appender Support**:
  - Standard Logback `AsyncAppender`
  - LMAX Disruptor-based `LoggingEventAsyncDisruptorAppender` (from logstash-logback-encoder)
- **Configurable Target Appender**: Switch between `ROLLING_FILE` and `CONSOLE` via a single property
- **Comprehensive Latency Measurement**: Tracks serialization and logging latency separately
- **System Information Collection**: Captures CPU, memory, storage, and JVM details using OSHI
- **Heap Monitoring**: Real-time JVM heap usage tracking during benchmark execution
- **Message Loss Validation**: Verifies all messages are written to log files (for file appenders)

## Project Structure

```
src/
├── main/
│   ├── kotlin/com/example/auditing/
│   │   └── logging/
│   │       └── FsyncRollingFileAppender.kt    # Custom appender with fsync support
│   └── resources/
│       └── logback-spring.xml                  # Logging configuration
└── test/
    └── kotlin/com/example/auditing/
        ├── BaseAsyncLoggingBenchmarkTest.kt    # Base benchmark test class
        ├── AsyncAppenderLoggingBenchmarkTest.kt # Standard AsyncAppender test
        ├── LoggingDisruptorBenchmarkTest.kt    # Disruptor-based appender test
        └── BenchmarkModels.kt                   # Data classes and utilities
```

## Configuration

### Logback Configuration (`logback-spring.xml`)

#### Target Appender

Switch between file and console output by changing `TARGET_APPENDER`:

```xml
<!-- Use file appender (default) -->
<property name="TARGET_APPENDER" value="ROLLING_FILE"/>

<!-- Use console appender -->
<property name="TARGET_APPENDER" value="CONSOLE"/>
```

#### Async Appender Selection

- **Standard AsyncAppender** (default): Run without any profile
- **Disruptor AsyncAppender**: Activate with `spring.profiles.active=disruptor`

#### FsyncRollingFileAppender Options

| Property | Default | Description |
|----------|---------|-------------|
| `fsyncEnabled` | `true` | Enable fsync after each write for guaranteed durability |
| `immediateFlush` | `true` | Flush Java buffers to OS immediately |

### JVM Configuration (`gradle.properties`)

```properties
jvm.minHeap=256m
jvm.maxHeap=1g
test.jvm.minHeap=256m
test.jvm.maxHeap=1g
```

## Running Benchmarks

### Standard AsyncAppender with File Output

```bash
./gradlew test --tests "AsyncAppenderLoggingBenchmarkTest"
```

### Disruptor AsyncAppender with File Output

```bash
./gradlew test --tests "LoggingDisruptorBenchmarkTest"
```

### Console Output (modify logback-spring.xml first)

Set `TARGET_APPENDER` to `CONSOLE` in `logback-spring.xml`, then run any test.

## Benchmark Output

The benchmark produces detailed output including:

### System Environment

```
=== System Environment ===

--- Operating System ---
Name:                    macOS
Version:                 14.5
Arch:                    aarch64

--- CPU ---
Model:                   Apple M1 Pro
Physical cores:          10
Logical cores:           10
Max frequency:           3.23 GHz

--- Memory ---
Total:                   32.00 GB
Type:                    LPDDR5
Speed:                   6400 MHz

--- Storage ---
APPLE SSD AP1024Q: 1000 GB (SSD)

--- JVM ---
Name:                    OpenJDK 64-Bit Server VM
Version:                 21.0.2
Vendor:                  Oracle Corporation
GC:                      G1 Young Generation, G1 Old Generation
```

### Appender Configuration

```
=== Appender Configuration (Standard AsyncAppender) ===
Target appender:         ROLLING_FILE
Queue size:              1024
Discarding threshold:    0
Never block:             false
Immediate flush:         true
Fsync enabled:           true
File:                    /tmp/logging-benchmark/audit.log
Max file size:           500.00 MB
Max history:             30
```

### Latency Results

```
=== Benchmark Results ===
Threads:                 200
Messages/thread:         50000
Total messages:          10000000
Wall-clock time:         45000 ms
Throughput:              222222.22 msgs/sec

--- Serialization Latency ---
  p50:                   1.23 µs
  p99:                   5.67 µs
  p99.5:                 8.90 µs

--- Logging Latency ---
  p50:                   2.34 µs
  p99:                   12.45 µs
  p99.5:                 25.67 µs

--- Total Latency (Serialization + Logging) ---
  p25:                   2.50 µs
  p50:                   3.57 µs
  p75:                   5.12 µs
  p99:                   18.12 µs
  p99.5:                 34.57 µs

Messages logged:         10000000
Message loss:            0
```

## Audit Log Entity

The benchmark serializes `AuditLog` entities to JSON:

```kotlin
data class AuditLog(
    val userId: String,
    val userIp: String,
    val time: Instant,
    val payload: Map<String, Any>
)
```

Example JSON output:

```json
{"userId":"user-1","userIp":"192.168.1.1","time":"2026-02-18T15:30:45.123Z","payload":{"messagePrefix":"B-a1b2c3d4","threadId":1,"messageNum":1,"action":"benchmark"}}
```

## Key Design Decisions

### Why fsync?

Standard file appenders flush data to the OS buffer but don't guarantee persistence to disk. The custom `FsyncRollingFileAppender` calls `fsync()` after each write to ensure data survives system crashes and power failures.

### Why separate serialization and logging latency?

Understanding where time is spent helps optimize the right component:
- High serialization latency: Consider object pooling or faster serializers
- High logging latency: Consider larger async queue, faster disk, or disabling fsync

### Why Disruptor-based appender?

The LMAX Disruptor provides lower latency and higher throughput than standard `BlockingQueue`-based async appenders, especially under high contention.

## Dependencies

- Spring Boot 4.0.2
- Logback (via Spring Boot)
- logstash-logback-encoder 8.0 (for `LoggingEventAsyncDisruptorAppender`)
- Jackson (for JSON serialization)
- OSHI 6.6.5 (for system information)
- JUnit 5 (for testing)

## Requirements

- Java 21+
- Gradle 9.3+
