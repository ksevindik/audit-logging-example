package com.example.auditing

import org.junit.jupiter.api.Test
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("disruptor")
class LoggingDisruptorBenchmarkTest : BaseAsyncLoggingBenchmarkTest() {
    @Test
    fun `benchmark disruptor appender logging performance and verify no message loss`() {
        super.runTest()
    }
}