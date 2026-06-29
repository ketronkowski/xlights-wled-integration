package com.ketronkowski.xlights

import com.ketronkowski.xlights.cli.XlightsWledCommand
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import picocli.CommandLine
import kotlin.system.exitProcess

@SpringBootApplication
class Application(private val applicationContext: ApplicationContext) {

    @Bean
    fun cliRunner(rootCommand: XlightsWledCommand): CliRunner = CliRunner(rootCommand, applicationContext)
}

class CliRunner(
    private val rootCommand: XlightsWledCommand,
    private val ctx: ApplicationContext,
) : org.springframework.boot.CommandLineRunner, ExitCodeGenerator {

    private var exitCode = 0

    override fun run(vararg args: String) {
        exitCode = CommandLine(rootCommand, SpringFactory(ctx))
            .execute(*args)
    }

    override fun getExitCode(): Int = exitCode
}

class SpringFactory(private val ctx: ApplicationContext) : CommandLine.IFactory {
    override fun <K : Any> create(clazz: Class<K>): K = try {
        ctx.getBean(clazz)
    } catch (_: Exception) {
        CommandLine.defaultFactory().create(clazz)
    }
}

fun main(args: Array<String>) {
    exitProcess(SpringApplication.exit(runApplication<Application>(*args)))
}
