package com.ketronkowski.xlights.domain

enum class ShowControllerAccess {
    REACHABLE,
    AUTH_REQUIRED,  // controller answered 401 and no credentials are configured
    AUTH_FAILED,    // controller answered 401 to the configured credentials
    UNREACHABLE,
}

data class ShowControllerStatus(
    val name: String,
    val ipAddress: String?,
    val type: String = "FPP",
    val access: ShowControllerAccess,
    val version: String? = null,
    val hostName: String? = null,
    val error: String? = null,
)
