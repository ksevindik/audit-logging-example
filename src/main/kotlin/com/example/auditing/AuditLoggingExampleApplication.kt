package com.example.auditing

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AuditLoggingExampleApplication

fun main(args: Array<String>) {
	runApplication<AuditLoggingExampleApplication>(*args)
}
