package com.ketronkowski.xlights.web

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
class UploadControllerTest {

    @LocalServerPort
    var port: Int = 0

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port").build() }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dataDir(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("xlights-wled-upload-test")
            registry.add("xlights.data-dir") { dir.toString() }
        }
    }

    @Test
    fun `status is 404 before upload, then round-trips the folder label after`() {
        client.get().uri("/api/upload/status").exchange()
            .expectStatus().isNotFound()

        val req = UploadRequest(
            networksXml = readFixture("xlights_networks.xml"),
            effectsXml  = readFixture("xlights_rgbeffects.xml"),
            folderLabel = "Christmas 2026",
        )

        val uploaded = client.post().uri("/api/upload")
            .contentType(MediaType.APPLICATION_JSON)
            .body(req)
            .exchange()
            .expectStatus().isOk()
            .expectBody(UploadStatus::class.java)
            .returnResult()
            .responseBody

        assertEquals("Christmas 2026", uploaded?.folderLabel)

        val status = client.get().uri("/api/upload/status").exchange()
            .expectStatus().isOk()
            .expectBody(UploadStatus::class.java)
            .returnResult()
            .responseBody

        assertEquals("Christmas 2026", status?.folderLabel)
    }

    private fun readFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("xlights/$name")!!.bufferedReader().readText()
}
