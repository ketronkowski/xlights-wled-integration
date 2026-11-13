package com.ketronkowski.xlights.domain

data class XLightsController(
    val name: String,
    val ipAddress: String?,
    val protocol: String,
    val totalChannels: Int,
    val models: List<XLightsModel>,
    // xLights' controller Vendor/Model (e.g. "WLED", or "FPP" for a Falcon Player).
    val vendor: String = "",
    val model: String = "",
) {
    // A Falcon Player is a show controller (it plays sequences and drives other
    // controllers), not a WLED pixel controller, so it gets no segment sync or backup.
    // Identified by what xLights says it is, never by its name.
    val isFpp: Boolean
        get() = vendor.contains("FPP", ignoreCase = true)
            || vendor.contains("Falcon Player", ignoreCase = true)
            || model.contains("FPP", ignoreCase = true)
}
