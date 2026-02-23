package com.example.auditing.logging

import ch.qos.logback.core.recovery.ResilientFileOutputStream
import ch.qos.logback.core.rolling.RollingFileAppender
import org.springframework.util.ReflectionUtils
import java.io.FileOutputStream

/**
 * A RollingFileAppender that performs fsync after writes to guarantee
 * log data is persisted to physical disk.
 *
 * Configuration options:
 * - fsyncEnabled: Enable/disable fsync (default: true)
 *
 * Note: fsync is slow (~1-10ms per call). Consider using this appender
 * behind an AsyncAppender to avoid blocking application threads.
 *
 * Example configuration:
 * ```xml
 * <appender name="FSYNC_FILE" class="com.example.auditing.logging.FsyncRollingFileAppender">
 *     <file>${LOG_FILE}</file>
 *     <fsyncEnabled>true</fsyncEnabled>
 *     <encoder>
 *         <pattern>...</pattern>
 *     </encoder>
 *     <rollingPolicy>...</rollingPolicy>
 * </appender>
 * ```
 */
class FsyncRollingFileAppender<E> : RollingFileAppender<E>() {

    /**
     * Enable or disable fsync after each write.
     * When enabled, guarantees data is written to physical disk.
     * Default: true
     */
    var fsyncEnabled: Boolean = true

    private var lastFsyncTime: Long = 0

    override fun subAppend(event: E) {
        super.subAppend(event)
        
        if (fsyncEnabled) {
            performFsync()
        }
    }

    private fun performFsync() {
        try {
            val os = outputStream
            if (os is FileOutputStream) {
                os.flush()
                os.fd.sync()
            } else if (os is ResilientFileOutputStream) {
                os.flush()
                val fosField = ReflectionUtils.findField(ResilientFileOutputStream::class.java,"fos")
                fosField!!.trySetAccessible()
                val fos = fosField.get(os) as FileOutputStream
                fos.fd.sync()
            } else {
                throw IllegalStateException("fysnc enabled but type of the OutputStream is not expected ${os.javaClass}")
            }
        } catch (e: Exception) {
            addError("Failed to fsync log file", e)
        }
    }

    override fun start() {
        if (fsyncEnabled) {
            addInfo("FsyncRollingFileAppender starting with fsync ENABLED")
        } else {
            addInfo("FsyncRollingFileAppender starting with fsync DISABLED")
        }
        super.start()
    }
}
