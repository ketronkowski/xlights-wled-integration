package com.ketronkowski.xlights.domain

data class WledBus(
    val start: Int,   // pixel start (inclusive, 0-based) — same semantics as WledSegment.start
    val len: Int,      // pixel count on this bus
)
