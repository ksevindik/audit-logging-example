package com.example.auditing

import org.junit.jupiter.api.Test
import org.springframework.test.context.ActiveProfiles


class AsyncAppenderLoggingBenchmarkTest : BaseAsyncLoggingBenchmarkTest() {
    @Test
    fun `benchmark async appender logging performance and verify no message loss`() {
        super.runTest()
    }
}