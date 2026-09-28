package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.domain.ValidationReport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.nio.file.Files

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ValidationControllerTest {

    @LocalServerPort
    var port: Int = 0

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port").build() }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dataDir(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("xlights-wled-validate-test")
            registry.add("xlights.data-dir") { dir.toString() }
        }
    }

    @Test
    fun `validate after upload reports both fixture controllers as unpaired`() {
        val req = UploadRequest(
            networksXml = readFixture("xlights_networks.xml"),
            effectsXml  = readFixture("xlights_rgbeffects.xml"),
            folderLabel = "Test Show",
        )
        client.post().uri("/api/upload")
            .contentType(MediaType.APPLICATION_JSON)
            .body(req)
            .exchange()
            .expectStatus().isOk()

        val report = client.post().uri("/api/validate")
            .contentType(MediaType.APPLICATION_JSON)
            .body(ValidateRequest())
            .exchange()
            .expectStatus().isOk()
            .expectBody(ValidationReport::class.java)
            .returnResult()
            .responseBody!!

        // Fixture controllers point at unreachable .local hostnames — both
        // should come back unpaired rather than failing the request.
        assertEquals(2, report.controllerValidations.size)
        assertTrue(report.controllerValidations.all { it.wledDevice == null })
        assertTrue(report.unpairedXlightsControllers.map { it.name }
            .containsAll(listOf("garage-wled", "roofline-wled")))
    }

    private fun readFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("xlights/$name")!!.bufferedReader().readText()
}
