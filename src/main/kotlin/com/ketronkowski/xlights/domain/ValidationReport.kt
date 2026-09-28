package com.ketronkowski.xlights.domain

enum class SegmentStatus {
    OK,
    MISSING,           // xLights model has no matching WLED segment by name
    RANGE_MISMATCH,    // segment exists but start/stop don't match xLights channel mapping
    NULL_NOT_OFF,      // gap model's segment exists and has correct range but is not disabled
}

data class SegmentValidation(
    val model: XLightsModel,
    val expectedPixelStart: Int,
    val expectedPixelStop: Int,
    val actualSegment: WledSegment?,
    val status: SegmentStatus,
)

data class ControllerValidation(
    val xLightsController: XLightsController,
    val wledDevice: WledDevice?,
    val expectedTotalLeds: Int,
    val totalLedsMatch: Boolean,
    val segmentValidations: List<SegmentValidation>,
    val orphanSegments: List<WledSegment>,  // WLED segments with no matching xLights model
    // Set when xLights model count exceeds the WLED firmware's maxseg limit
    val segmentLimitWarning: String? = null,
)

data class ValidationReport(
    val controllerValidations: List<ControllerValidation>,
    val unpairedXlightsControllers: List<XLightsController>,
    val unpairedWledDevices: List<WledDevice>,
) {
    val hasErrors: Boolean
        get() = unpairedXlightsControllers.isNotEmpty()
            || controllerValidations.any { cv ->
                !cv.totalLedsMatch
                    || cv.segmentValidations.any { it.status != SegmentStatus.OK }
                    || cv.orphanSegments.isNotEmpty()
            }
}
