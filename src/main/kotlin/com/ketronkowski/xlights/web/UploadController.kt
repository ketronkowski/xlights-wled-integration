package com.ketronkowski.xlights.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText

data class UploadRequest(
    val networksXml: String,
    val effectsXml: String,
    val folderLabel: String,
)

data class UploadStatus(
    val folderLabel: String,
    val uploadedAt: Instant,
)

@RestController
@RequestMapping("/api")
class UploadController(
    @Value("\${xlights.data-dir}") dataDir: String,
    private val objectMapper: ObjectMapper,
) {
    private val xlightsDir: Path = Path.of(dataDir, "xlights")
    private val metaFile: Path = xlightsDir.resolve("upload-meta.json")

    @PostMapping("/upload")
    fun upload(@RequestBody req: UploadRequest): UploadStatus {
        Files.createDirectories(xlightsDir)
        Files.writeString(xlightsDir.resolve("xlights_networks.xml"), req.networksXml)
        Files.writeString(xlightsDir.resolve("xlights_rgbeffects.xml"), req.effectsXml)

        val status = UploadStatus(folderLabel = req.folderLabel, uploadedAt = Instant.now())
        Files.writeString(metaFile, objectMapper.writeValueAsString(status))
        return status
    }

    @GetMapping("/upload/status")
    fun status(): ResponseEntity<UploadStatus> {
        if (!metaFile.exists()) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(objectMapper.readValue(metaFile.readText(), UploadStatus::class.java))
    }
}
