plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

group = "com.ketronkowski"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26
    }
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.jackson.dataformat.xml)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.picocli)
    implementation(libs.jmdns)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.bootJar {
    archiveFileName.set("xlights-wled-integration.jar")
}

tasks.jar {
    enabled = false
}
