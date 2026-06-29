package com.ketronkowski.xlights.domain

data class XLightsModel(
    val name: String,
    val controllerName: String,
    val startChannel: Int,    // 1-based, absolute within the controller's channel range
    val channelCount: Int,
    val isNull: Boolean,      // true = gap/placeholder model, should map to disabled WLED segment
)
