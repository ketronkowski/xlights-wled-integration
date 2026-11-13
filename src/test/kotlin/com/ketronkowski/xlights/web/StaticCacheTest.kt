package com.ketronkowski.xlights.web

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.nio.file.Files

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticCacheTest {

    @LocalServerPort
    var port: Int = 0

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port").build() }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dataDir(registry: DynamicPropertyRegistry) {
            registry.add("xlights.data-dir") { Files.createTempDirectory("xlights-wled-cache-test").toString() }
        }
    }

    @Test
    fun `app js tells the browser to revalidate on every load`() {
        val lastModified = client.get().uri("/app.js").exchange()
            .expectStatus().isOk()
            .expectHeader().valueMatches("Cache-Control", ".*no-cache.*")
            .expectBody(ByteArray::class.java)
            .returnResult()
            .responseHeaders.getFirst("Last-Modified")!!

        client.get().uri("/app.js").header("If-Modified-Since", lastModified).exchange()
            .expectStatus().isNotModified()
    }
}
