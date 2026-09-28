package com.ketronkowski.xlights.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class AppConfig {

    @Bean
    fun restClient(): RestClient {
        // JDK HttpClient uses the OS resolver, so .local mDNS hostnames
        // (e.g. wled-octa1.local) resolve correctly wherever mDNS is reachable.
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(Duration.ofSeconds(30))
        }
        return RestClient.builder()
            .requestFactory(requestFactory)
            .build()
    }

    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
