package com.ketronkowski.xlights.fpp

import com.ketronkowski.xlights.domain.ShowControllerAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class FppApiClientTest {

    private val info = """{"HostName":"fpp","Platform":"Raspberry Pi","Version":"9.1"}"""

    private fun client(password: String = ""): Pair<FppApiClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return FppApiClient(builder.build(), "admin", password) to server
    }

    @Test
    fun `reachable FPP reports its version`() {
        val (fpp, server) = client()
        server.expect(requestTo("http://10.0.0.5/api/system/info"))
            .andRespond(withSuccess(info, MediaType.APPLICATION_JSON))

        val status = fpp.checkAccess("fpp", "10.0.0.5")

        assertEquals(ShowControllerAccess.REACHABLE, status.access)
        assertEquals("9.1", status.version)
    }

    @Test
    fun `401 without configured credentials means credentials are required`() {
        val (fpp, server) = client()
        server.expect(requestTo("http://10.0.0.5/api/system/info")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        assertEquals(ShowControllerAccess.AUTH_REQUIRED, fpp.checkAccess("fpp", "10.0.0.5").access)
    }

    @Test
    fun `configured credentials are sent as basic auth, and a 401 means they were rejected`() {
        val (fpp, server) = client(password = "secret")
        server.expect(requestTo("http://10.0.0.5/api/system/info"))
            .andExpect(header("Authorization", "Basic YWRtaW46c2VjcmV0"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        assertEquals(ShowControllerAccess.AUTH_FAILED, fpp.checkAccess("fpp", "10.0.0.5").access)
        server.verify()
    }

    @Test
    fun `looksLikeFpp needs FPP shaped system info`() {
        val (fpp, server) = client()
        server.expect(requestTo("http://10.0.0.7/api/system/info"))
            .andRespond(withSuccess(info, MediaType.APPLICATION_JSON))
        server.expect(requestTo("http://10.0.0.8/api/system/info")).andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertTrue(fpp.looksLikeFpp("10.0.0.7"))
        assertFalse(fpp.looksLikeFpp("10.0.0.8"))
    }
}
