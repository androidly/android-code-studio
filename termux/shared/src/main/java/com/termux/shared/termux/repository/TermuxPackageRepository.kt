package com.termux.shared.termux.repository

import android.content.Context
import android.os.Build
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences

object TermuxPackageRepository {

    private const val OFFICIAL_TERMUX_PACKAGE_NAME = "com.termux"
    private const val OFFICIAL_TERMUX_MAIN_REPO = "https://packages-cf.termux.dev/apt/termux-main"
    private const val DEFAULT_COMPATIBLE_ACS_ARM64_REPO =
        "https://acs-packages-arm64-64c907.gitlab.io"
    private const val DEFAULT_COMPATIBLE_ACS_ARM_REPO =
        "https://acs-packages-arm-949de1.gitlab.io"
    private const val DEFAULT_NPM_REGISTRY = "https://registry.npmjs.org"

    @JvmStatic
    fun usesOfficialTermuxPackage(): Boolean {
        return TermuxConstants.TERMUX_PACKAGE_NAME == OFFICIAL_TERMUX_PACKAGE_NAME
    }

    @JvmStatic
    fun getDefaultMainRepo(): String {
        if (usesOfficialTermuxPackage()) {
            return OFFICIAL_TERMUX_MAIN_REPO
        }
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            primaryAbi.startsWith("arm64") -> DEFAULT_COMPATIBLE_ACS_ARM64_REPO
            primaryAbi.startsWith("armeabi") || primaryAbi.startsWith("arm") ->
                DEFAULT_COMPATIBLE_ACS_ARM_REPO
            else -> DEFAULT_COMPATIBLE_ACS_ARM64_REPO
        }
    }

    @JvmStatic
    fun getConfiguredMainRepo(context: Context): String? {
        val preferences = TermuxAppSharedPreferences.build(context, false) ?: return null
        return normalizeRepoUrl(preferences.mainPackageRepositoryUrl)
    }

    @JvmStatic
    fun setConfiguredMainRepo(context: Context, value: String?) {
        val preferences = TermuxAppSharedPreferences.build(context, false) ?: return
        preferences.setMainPackageRepositoryUrl(normalizeRepoUrl(value).orEmpty())
    }

    @JvmStatic
    fun getEffectiveMainRepo(context: Context): String {
        return getConfiguredMainRepo(context) ?: getDefaultMainRepo()
    }

    @JvmStatic
    fun getDefaultNpmRegistry(): String {
        return DEFAULT_NPM_REGISTRY
    }

    @JvmStatic
    fun getConfiguredNpmRegistry(context: Context): String? {
        val preferences = TermuxAppSharedPreferences.build(context, false) ?: return null
        return normalizeRegistryUrl(preferences.npmRegistryUrl)
    }

    @JvmStatic
    fun setConfiguredNpmRegistry(context: Context, value: String?) {
        val preferences = TermuxAppSharedPreferences.build(context, false) ?: return
        preferences.setNpmRegistryUrl(normalizeRegistryUrl(value).orEmpty())
    }

    @JvmStatic
    fun getEffectiveNpmRegistry(context: Context): String {
        return getConfiguredNpmRegistry(context) ?: getDefaultNpmRegistry()
    }

    @JvmStatic
    fun getExpectedMainSources(context: Context): String {
        val repo = getEffectiveMainRepo(context)
        return if (usesOfficialTermuxPackage()) {
            buildString {
                appendLine("# The main termux repository, with cloudflare cache")
                appendLine("deb $repo stable main")
                appendLine("# The main termux repository, without cloudflare cache")
                appendLine("# deb https://packages.termux.dev/apt/termux-main/ stable main")
            }
        } else {
            "deb [trusted=yes] $repo stable main\n"
        }
    }

    @JvmStatic
    fun matchesExpectedMainSources(context: Context, currentSources: String?): Boolean {
        if (currentSources.isNullOrBlank()) {
            return false
        }
        return extractRepoLines(currentSources) == extractRepoLines(getExpectedMainSources(context))
    }

    @JvmStatic
    fun normalizeRepoUrl(value: String?): String? {
        return value
            ?.trim()
            ?.removeSuffix("/")
            ?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun normalizeRegistryUrl(value: String?): String? {
        return normalizeRepoUrl(value)
    }

    private fun extractRepoLines(sources: String): List<String> {
        return sources
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("deb ") }
            .map(::normalizeRepoLine)
            .toList()
    }

    private fun normalizeRepoLine(line: String): String {
        val tokens = line.split(Regex("\\s+")).toMutableList()
        val repoIndex = tokens.indexOfFirst { token ->
            token.startsWith("https://") || token.startsWith("http://")
        }
        if (repoIndex >= 0) {
            tokens[repoIndex] = tokens[repoIndex].removeSuffix("/")
        }
        return tokens.joinToString(" ")
    }
}
