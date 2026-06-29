package com.ketronkowski.xlights.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

@Configuration
class AppConfig {

    @Bean
    fun webClient(): WebClient = WebClient.builder()
        .codecs { it.defaultCodecs().maxInMemorySize(1024 * 1024) }
        .build()

    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()
}
