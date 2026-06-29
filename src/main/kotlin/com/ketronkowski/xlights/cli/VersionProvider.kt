package com.ketronkowski.xlights.cli

import picocli.CommandLine.IVersionProvider

class VersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> =
        arrayOf(VersionProvider::class.java.getPackage()?.implementationVersion ?: "dev")
}
