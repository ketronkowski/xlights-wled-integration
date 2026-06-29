package com.ketronkowski.xlights.domain

data class XLightsController(
    val name: String,
    val ipAddress: String?,
    val protocol: String,
    val totalChannels: Int,
    val models: List<XLightsModel>,
)
