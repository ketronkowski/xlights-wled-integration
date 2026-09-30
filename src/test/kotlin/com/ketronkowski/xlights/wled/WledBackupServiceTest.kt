package com.ketronkowski.xlights.wled

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.ketronkowski.xlights.domain.WledDevice
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.nio.file.Path
import kotlin.io.path.exists

class WledBackupServiceTest {

    private lateinit var apiClient: WledApiClient
    private lateinit var service: WledBackupService

    @TempDir
    lateinit var dataDir: Path

    private val device = WledDevice(
        name            = "garage-wled",
        ipAddress       = "192.168.1.100",
        firmwareVersion = "0.15.0",
        totalLeds       = 300,
        bytesPerPixel   = 3,
        maxSegments     = 10,
        segments        = emptyList(),
    )

    @BeforeEach
    fun setUp() {
        apiClient = mock(WledApiClient::class.java)
        val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        service = WledBackupService(apiClient, objectMapper, dataDir.toString())
    }

    @Test
    fun `backup downloads and persists cfg and presets`() {
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "cfg.json")).thenReturn("""{"hw":{}}""")
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "presets.json")).thenReturn("""{"0":{}}""")

        val record = service.backup(device)

        assertTrue(record.hasCfg)
        assertTrue(record.hasPresets)
        val dir = dataDir.resolve("backups").resolve("garage-wled").resolve(record.timestamp.toEpochMilli().toString())
        assertTrue(dir.resolve("cfg.json").exists())
        assertTrue(dir.resolve("presets.json").exists())
        assertTrue(dir.resolve("meta.json").exists())
    }

    @Test
    fun `backup succeeds without presets when device has none`() {
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "cfg.json")).thenReturn("""{"hw":{}}""")
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "presets.json")).thenThrow(RuntimeException("404"))

        val record = service.backup(device)

        assertTrue(record.hasCfg)
        assertFalse(record.hasPresets)
    }

    @Test
    fun `backup fails hard when cfg download fails`() {
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "cfg.json")).thenThrow(RuntimeException("connection refused"))

        assertThrows(RuntimeException::class.java) { service.backup(device) }
    }

    @Test
    fun `listBackups returns previously written records`() {
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "cfg.json")).thenReturn("""{"hw":{}}""")
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "presets.json")).thenReturn("""{}""")

        val record = service.backup(device)

        val all = service.listBackups()
        assertEquals(1, all.size)
        assertEquals(record.controllerName, all.first().controllerName)

        val filtered = service.listBackups("garage-wled")
        assertEquals(1, filtered.size)

        assertTrue(service.listBackups("nonexistent").isEmpty())
    }

    @Test
    fun `restore uploads backed-up files and reboots the device`() {
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "cfg.json")).thenReturn("""{"hw":{}}""")
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "presets.json")).thenReturn("""{"0":{}}""")
        val record = service.backup(device)

        service.restore("garage-wled", record.timestamp.toEpochMilli(), "192.168.1.100")

        verify(apiClient).uploadRaw("192.168.1.100", "cfg.json", """{"hw":{}}""")
        verify(apiClient).uploadRaw("192.168.1.100", "presets.json", """{"0":{}}""")
        verify(apiClient).reboot("192.168.1.100")
    }

    @Test
    fun `restore throws for unknown backup`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.restore("garage-wled", 123456789L, "192.168.1.100")
        }
    }

    @Test
    fun `deleteAllBackups removes the entire backups tree`() {
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "cfg.json")).thenReturn("""{"hw":{}}""")
        Mockito.`when`(apiClient.downloadRaw("192.168.1.100", "presets.json")).thenReturn("""{"0":{}}""")
        service.backup(device)
        val backupsDir = dataDir.resolve("backups")
        assertTrue(backupsDir.exists())

        val result = service.deleteAllBackups()

        assertTrue(result)
        assertFalse(backupsDir.exists())
        assertTrue(service.listBackups().isEmpty())
    }

    @Test
    fun `deleteAllBackups returns false harmlessly when no backups exist`() {
        assertFalse(service.deleteAllBackups())
    }
}
