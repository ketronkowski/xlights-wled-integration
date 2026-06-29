package com.ketronkowski.xlights.cli

import org.springframework.stereotype.Component
import picocli.CommandLine.Command

@Component
@Command(
    name        = "xlights-wled",
    description = ["Validate and sync xLights controller configuration with WLED devices"],
    subcommands = [ValidateCommand::class],
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider::class,
)
class XlightsWledCommand : Runnable {
    override fun run() {
        // Handled by picocli when no subcommand is given (prints help)
    }
}
