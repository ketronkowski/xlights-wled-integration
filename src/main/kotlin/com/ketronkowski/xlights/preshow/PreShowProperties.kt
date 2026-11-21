package com.ketronkowski.xlights.preshow

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalDate
import java.time.LocalTime

/**
 * Settings for the daily pre-show check. Everything site-specific (hosts, entity ids,
 * URLs, addresses) comes from the deployment, never from this repo.
 */
@ConfigurationProperties("preshow")
data class PreShowProperties(
    /** Scheduled runs only happen when true. Manual runs from the UI or API always work. */
    val enabled: Boolean = false,
    val zone: String = "America/New_York",
    /** First and last show night (inclusive). Scheduled runs are skipped outside it. */
    val seasonStart: LocalDate? = null,
    val seasonEnd: LocalDate? = null,
    /**
     * First real show night, when it differs from [seasonStart] (dry runs before it).
     * Before this date a missing show playlist in FPP's schedule is a warning, not a blocker.
     */
    val firstShowNight: LocalDate? = null,
    /** When the show starts each night; used to check FPP's schedule. */
    val showStart: LocalTime = LocalTime.of(17, 0),
    val passes: List<PassSchedule> = listOf(
        PassSchedule("early", "0 0 14 * * *"),
        PassSchedule("recheck", "0 15 16 * * *"),
        PassSchedule("final", "0 50 16 * * *", pushAlways = true, emailAlways = true),
        PassSchedule("last-look", "0 58 16 * * *"),
    ),
    val wled: WledChecks = WledChecks(),
    val fpp: FppChecks = FppChecks(),
    val ha: HomeAssistant = HomeAssistant(),
    val web: List<WebCheck> = emptyList(),
    val email: Email = Email(),
    /** Run files older than this many days are deleted. */
    val keepDays: Long = 60,
) {
    data class PassSchedule(
        val name: String,
        /** Spring cron (second minute hour day month weekday) in [zone]. */
        val cron: String,
        /** Push after every run of this pass; otherwise only when something failed or warned. */
        val pushAlways: Boolean = false,
        /** Email after every run of this pass; otherwise only when something failed. */
        val emailAlways: Boolean = false,
    )

    data class WledChecks(
        val expectedFirmware: String = "16.0.1",
        /** Boot preset every show board should sit on before the show ("Black"). 0 = don't check. */
        val expectedPreset: Int = 1,
        val minFreeHeapBytes: Int = 30_000,
        val minWifiRssi: Int = -75,
        /** Tries per board before it counts as not answering. */
        val attempts: Int = 3,
        val retryDelaySeconds: Long = 5,
        /** xLights controllers to leave out (not in the show). */
        val ignore: List<String> = emptyList(),
    )

    data class FppChecks(
        /** FPP address. Blank = take it from the xLights layout. */
        val host: String = "",
        /** Playlists that must be scheduled to be running at show start. */
        val showPlaylists: List<String> = listOf("Remote"),
        /** Playlists whose files must all be present. */
        val validatePlaylists: List<String> = listOf("Remote", "Background"),
        /** Expected audio volume, or null to skip. */
        val expectedVolume: Int? = null,
        val maxCpuTempC: Double = 75.0,
        val minMediaFreePercent: Double = 15.0,
        val maxClockDriftSeconds: Long = 5,
    )

    data class HomeAssistant(
        /** e.g. http://ha.example:8123. Blank = power checks and pushes are skipped. */
        val url: String = "",
        val token: String = "",
        /** notify service for the push, e.g. mobile_app_pixel_10_pro_xl */
        val notifyService: String = "",
        val voltageEntity: String = "",
        val currentEntity: String = "",
        /** Power sensor in watts, used for the standby check when there is no current sensor (A = W / 120 V). */
        val powerEntity: String = "",
        /** The show circuit's own relay (e.g. the Shelly switch). Off = no power to the show. */
        val circuitSwitchEntity: String = "",
        val minVoltage: Double = 110.0,
        /** Highest believable standby current with the show black, in amps. 0 = skip. */
        val maxStandbyAmps: Double = 0.0,
        /** Label → switch entity id for the smart plugs feeding show boards. */
        val plugs: Map<String, String> = emptyMap(),
        /** input_boolean that turns scheduled runs off (kill switch). Blank = no toggle. */
        val enabledEntity: String = "",
        /** Sensor the app writes its latest result to, for dashboards and the "did not run" backstop. */
        val statusEntity: String = "sensor.preshow_status",
        /** Link put on the push so a tap opens the report. */
        val reportUrl: String = "",
        /** binary_sensor that is on while the iPhone camera feed is live (MediaMTX path ready). Blank = skip. */
        val phoneFeedEntity: String = "",
        /** sensor naming the input the stream is using now ("phone", or a fallback camera). */
        val feedSourceEntity: String = "",
        /** Value of [feedSourceEntity] that means the iPhone. */
        val phoneSourceName: String = "phone",
    )

    data class WebCheck(
        val name: String,
        val url: String,
        /** true = a failure is a show blocker; false = warning. */
        val blocker: Boolean = false,
        /** Text the page must contain, if any. */
        val contains: String = "",
        val minCertDays: Long = 14,
    )

    data class Email(
        val to: String = "",
        val from: String = "",
        val host: String = "smtp.gmail.com",
        val port: Int = 587,
        val username: String = "",
        val password: String = "",
    ) {
        val configured: Boolean get() = to.isNotBlank() && from.isNotBlank() && password.isNotBlank()
    }
}
