package com.ketronkowski.xlights.wled

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

@Component
class WledDiscovery {

    private val log = LoggerFactory.getLogger(WledDiscovery::class.java)

    companion object {
        private const val WLED_SERVICE_TYPE = "_wled._tcp.local."
    }

    fun discoverDevices(timeoutSeconds: Int = 5): List<String> {
        log.info("Discovering WLED devices via mDNS (timeout={}s)...", timeoutSeconds)
        val ips = mutableListOf<String>()

        JmDNS.create().use { jmdns ->
            val services: Array<ServiceInfo> = jmdns.list(WLED_SERVICE_TYPE, timeoutSeconds * 1000L)
            for (service in services) {
                val addresses = service.inet4Addresses
                if (addresses.isEmpty()) continue
                val ip = addresses[0].hostAddress
                log.info("Found WLED device '{}' at {}", service.name, ip)
                ips += ip
            }
        }

        if (ips.isEmpty()) {
            log.warn("No WLED devices discovered on the local network via mDNS")
        }
        return ips
    }
}
