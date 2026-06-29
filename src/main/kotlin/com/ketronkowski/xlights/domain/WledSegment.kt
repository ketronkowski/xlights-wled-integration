package com.ketronkowski.xlights.domain

data class WledSegment(
    val id: Int,
    val name: String?,
    val start: Int,   // pixel start (inclusive, 0-based)
    val stop: Int,    // pixel stop (exclusive)
    val on: Boolean,
)
