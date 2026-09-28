package com.ketronkowski.xlights.domain

import java.time.Instant

data class WledBackupRecord(
    val controllerName: String,
    val ipAddress: String,
    val timestamp: Instant,
    val hasCfg: Boolean,
    val hasPresets: Boolean,
)
