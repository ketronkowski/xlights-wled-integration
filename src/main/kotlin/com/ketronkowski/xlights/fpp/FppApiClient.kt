package com.ketronkowski.xlights.fpp

import com.ketronkowski.xlights.domain.ShowControllerAccess
import com.ketronkowski.xlights.domain.ShowControllerStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.util.Base64

/**
 * Checks whether a Falcon Player (FPP) show controller can be reached, using its
 * `/api/system/info` endpoint. When FPP's UI password is enabled the API answers
 * 401; set `FPP_USERNAME` (default `admin`) and `FPP_PASSWORD` to send HTTP basic
 * credentials.
 */
@Component
class FppApiClient(
    private val restClient: RestClient,
    @Value("\${xlights.fpp.username:admin}") private val username: String,
    @Value("\${xlights.fpp.password:}") private val password: String,
) {
    private val log = LoggerFactory.getLogger(FppApiClient::class.java)

    private val hasCredentials get() = password.isNotEmpty()

    fun checkAccess(name: String, host: String?): ShowControllerStatus {
        if (host.isNullOrBlank()) {
            return ShowControllerStatus(name, host, access = ShowControllerAccess.UNREACHABLE, error = "no address in xLights config")
        }
        return try {
            val info = fetchInfo(host)
            ShowControllerStatus(
                name      = name,
                ipAddress = host,
                access    = ShowControllerAccess.REACHABLE,
                version   = info["Version"]?.toString(),
                hostName  = info["HostName"]?.toString(),
            )
        } catch (e: HttpClientErrorException) {
            if (e.statusCode == HttpStatus.UNAUTHORIZED || e.statusCode == HttpStatus.FORBIDDEN) {
                val access = if (hasCredentials) ShowControllerAccess.AUTH_FAILED else ShowControllerAccess.AUTH_REQUIRED
                log.warn("FPP '{}' at {} refused access ({}), credentials configured: {}", name, host, e.statusCode, hasCredentials)
                ShowControllerStatus(name, host, access = access, error = "HTTP ${e.statusCode.value()}")
            } else {
                log.warn("FPP '{}' at {} answered {}", name, host, e.statusCode)
                ShowControllerStatus(name, host, access = ShowControllerAccess.UNREACHABLE, error = "HTTP ${e.statusCode.value()}")
            }
        } catch (e: Exception) {
            log.warn("Cannot reach FPP '{}' at {}: {}", name, host, e.message)
            ShowControllerStatus(name, host, access = ShowControllerAccess.UNREACHABLE, error = e.message)
        }
    }

    /**
     * True when [host] answers like an FPP. Used to recognise an FPP that xLights
     * lists under another vendor (e.g. a cape vendor). A 401 isn't proof, so it's
     * only counted when the reply carries FPP-shaped JSON.
     */
    fun looksLikeFpp(host: String): Boolean =
        try {
            val info = fetchInfo(host)
            info.containsKey("Version") && (info.containsKey("Platform") || info.containsKey("HostName"))
        } catch (e: Exception) {
            false
        }

    private fun fetchInfo(host: String): Map<String, Any?> {
        val req = restClient.get().uri("http://$host/api/system/info")
        if (hasCredentials) {
            val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            req.header("Authorization", "Basic $token")
        }
        return req.retrieve().body<Map<String, Any?>>() ?: emptyMap()
    }
}
