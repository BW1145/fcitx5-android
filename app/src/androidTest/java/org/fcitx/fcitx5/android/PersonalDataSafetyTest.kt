/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.data.clipboard.ClearUrlsRuleFilter
import org.fcitx.fcitx5.android.input.clipboard.ContentTokenizer
import org.fcitx.fcitx5.android.utils.extract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PersonalDataSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun linkFilter() = ClearUrlsRuleFilter(
        context.assets.open("clearurls-rules.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    )

    @Test
    fun clearUrlsDecodesOnlyTheOuterRedirectEncoding() {
        val target = "https://example.com/a%2Fb?q=rock%26roll#part%23one"
        val filter = linkFilter()
        for (encoded in listOf(Uri.encode(target), Uri.encode(Uri.encode(target)))) {
            assertEquals(target, filter.transform("https://www.google.com/url?q=$encoded"))
        }
    }

    @Test
    fun clearUrlsPreservesDirectAndSchemalessRedirectTargets() {
        val target = "https://example.com/a%2Fb?q=rock%26roll%2525#part%23one"
        val filter = linkFilter()
        assertEquals(target, filter.transform("https://href.li/?$target"))
        assertEquals(
            "example.com/a%2Fb",
            filter.transform("https://www.google.com/amp/s/example.com/a%2Fb")
        )
    }

    @Test
    fun clearUrlsPreservesParameterValuesWhenRemovingTracking() {
        assertEquals(
            "https://example.com/?q=rock%26roll#part%23one",
            linkFilter().transform("https://example.com/?q=rock%26roll&utm_source=share#part%23one")
        )
    }

    @Test
    fun zipRejectsEscapingDirectoryWithoutSideEffects() = withExtractionDirectory { root, dest ->
        val outside = root.resolve("created-outside")
        assertRejected(zip("../created-outside/" to ""), dest)
        assertFalse("A rejected ZIP must not create an outside directory", outside.exists())
    }

    @Test
    fun zipRejectsSiblingPrefixFile() = withExtractionDirectory { root, dest ->
        val sibling = root.resolve("extract-sibling").apply { check(mkdir()) }
        assertRejected(zip("../extract-sibling/leaked.txt" to "outside"), dest)
        assertFalse("A sibling sharing the destination prefix is outside it", sibling.resolve("leaked.txt").exists())
    }

    @Test
    fun zipExtractsNestedEntriesWithoutExplicitParents() = withExtractionDirectory { _, dest ->
        val archive = zip("nested/deep/content.txt" to "保存的内容", "empty/deep/" to "")
        val entries = ZipInputStream(ByteArrayInputStream(archive)).use { it.extract(dest) }
        assertEquals("保存的内容", dest.resolve("nested/deep/content.txt").readText())
        assertTrue(dest.resolve("empty/deep").isDirectory)
        assertEquals(listOf("empty", "nested"), entries.map { it.name }.sorted())
    }

    @Test
    fun tokenizationPreservesTextOnSupportedAndroidVersions() {
        // Runs the java.text fallback on API 23 and Android ICU on newer test devices.
        val text = "你好，world! café\n👩‍💻🙂 第二行"
        val ranges = ContentTokenizer.ranges(text)
        assertTrue(ranges.isNotEmpty())
        assertTrue(ranges.all { it.first >= 0 && it.last < text.length && !text.substring(it).isBlank() })
        assertTrue(ranges.zipWithNext().all { (before, after) -> before.last < after.first })
        assertEquals(
            text.filterNot { it.isWhitespace() },
            ranges.joinToString("") { text.substring(it) }.filterNot { it.isWhitespace() }
        )
        assertTrue(ranges.any { text.substring(it) == "world" })
        assertTrue(ContentTokenizer.ranges(" \n\t ").isEmpty())
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { archive ->
            for ((name, content) in entries) {
                archive.putNextEntry(ZipEntry(name))
                archive.write(content.toByteArray(Charsets.UTF_8))
                archive.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun assertRejected(archive: ByteArray, dest: File) {
        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { it.extract(dest) }
            fail("Expected the escaping ZIP entry to be rejected")
        } catch (_: SecurityException) {
            // Our boundary validation on Android 6–13.
        } catch (_: ZipException) {
            // Android 14+ can reject the entry before it reaches extract().
        }
    }

    private inline fun withExtractionDirectory(block: (File, File) -> Unit) {
        val root = File.createTempFile("data-safety-", ".tmp", context.cacheDir)
        check(root.delete() && root.mkdir())
        try {
            block(root, root.resolve("extract").apply { check(mkdir()) })
        } finally {
            root.deleteRecursively()
        }
    }
}
