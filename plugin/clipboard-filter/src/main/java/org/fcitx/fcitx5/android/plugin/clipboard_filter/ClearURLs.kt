/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.clipboard_filter

import android.net.Uri
import android.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

typealias RegexAsString = @Serializable(with = RegexSerializer::class) Regex

object ClearURLs {
    @Serializable
    data class ClearURLsProvider(
        /** all patterns starts with https? */
        val urlPattern: RegexAsString,
        val completeProvider: Boolean = false,
        val rules: List<RegexAsString> = emptyList(),
        val rawRules: List<RegexAsString> = emptyList(),
        val referralMarketing: List<RegexAsString> = emptyList(),
        val exceptions: List<RegexAsString> = emptyList(),
        val redirections: List<RegexAsString> = emptyList(),
        val forceRedirection: Boolean = false
    )

    private val providersSerializer: KSerializer<Map<String, ClearURLsProvider>> = serializer()
    private val catalogSerializer: KSerializer<Map<String, Map<String, ClearURLsProvider>>> =
        MapSerializer(serializer(), providersSerializer)

    private var catalog: Map<String, ClearURLsProvider>? = null

    fun initCatalog(rawRules: String) {
        if (catalog != null) return
        catalog = Json.decodeFromString(catalogSerializer, rawRules)["providers"]
    }

    private val urlPattern = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val uriSchemePattern = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)

    fun transform(text: String): String {
        if (!urlPattern.matchesAt(text, 0)) return text
        val map = catalog ?: throw IllegalStateException("Catalog is unavailable")
        return transformWith(text, map)
    }

    private fun transformWith(url: String, map: Map<String, ClearURLsProvider>): String {
        var x = url
        var matched = false
        for ((_, provider) in map) {
            // matches url pattern
            if (!provider.urlPattern.containsMatchIn(x))
                continue
            // not in exceptions
            if (provider.exceptions.any { it.containsMatchIn(x) })
                continue
            matched = true
            // apply redirections
            provider.redirections.forEach { redirection ->
                redirection.matchAt(x, 0)?.groupValues?.getOrNull(1)?.let {
                    x = decodeURL(it)
                    log(if (BuildConfig.DEBUG) "$url ~> $x" else "(redirect)")
                    return x
                }
            }
            provider.rawRules.forEach { rawRule ->
                x = rawRule.replace(x, "")
            }
            /**
             * apply rules and referralMarketing
             * https://docs.clearurls.xyz/1.26.1/specs/rules/#referralmarketing
             * https://github.com/ClearURLs/Addon/blob/deec80b763179fa5c3559a37e3c9a6f1b28d0886/clearurls.js#L449
             */
            val rules = provider.rules + provider.referralMarketing
            val uri = Uri.parse(x)
            x = uri.buildUpon()
                .encodedQuery(filterParams(uri.encodedQuery, rules))
                /**
                 * clear #fragments too
                 * https://github.com/ClearURLs/Addon/blob/deec80b763179fa5c3559a37e3c9a6f1b28d0886/clearurls.js#L109
                 * preserve the fragment's existing encoding
                 * https://github.com/ClearURLs/Addon/blob/d8da43ac297e5df51a9c7276579ac3332adfa801/core_js/utils/URLHashParams.js#L65
                 */
                .encodedFragment(filterParams(uri.encodedFragment, rules))
                .toString()
        }
        if (matched) {
            log(if (BuildConfig.DEBUG) "$url -> $x" else "(clear)")
        }
        return x
    }

    private fun decodeURL(str: String): String {
        var target = str
        // Rules can capture either a URL directly or an encoded redirect parameter.
        // Once the scheme is visible, remaining escapes belong to the target URL.
        while (!uriSchemePattern.containsMatchIn(target) && !target.startsWith("//")) {
            val decoded = Uri.decode(target)
            // Some rules capture a bare host/path, whose escapes must also survive.
            if (decoded == target) return str
            target = decoded
        }
        return target
    }

    private fun filterParams(params: String?, rules: List<Regex>): String? {
        if (params.isNullOrEmpty()) return params
        // Match decoded keys while keeping each retained parameter byte for byte.
        return params.split('&').filter { param ->
            val name = Uri.decode(param.substringBefore('=').replace("+", " "))
            rules.none { it.matches(name) }
        }.joinToString("&").ifEmpty { null }
    }

    private fun log(msg: String) {
        Log.d("ClearURLs", msg)
    }
}
