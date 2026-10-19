/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.core.PersonalDefaults
import org.fcitx.fcitx5.android.core.data.DataManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Run across actual full -> lite -> full APK replacements, without clearing app data. */
class PersonalPackagingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = context.getExternalFilesDir(null) ?: context.filesDir
    private val prefs get() = context.getSharedPreferences("packaging-upgrade-test", 0)
    private val paths = listOf(
        "data/rime/default.custom.yaml", "data/rime/upgrade-preservation.user.txt",
        "data/rime/zh-hans-t-essay-bgw-compact.gram", "data/rime/predict.db",
        "config/profile"
    )

    private fun sync() {
        DataManager.sync()
        PersonalDefaults.install(context)
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun prepareFullUpgrade() {
        sync()
        assertTrue(DataManager.dataDir.resolve("usr/share/libime/zh_CN.lm").isFile)
        root.resolve("data/rime/default.custom.yaml").appendText("\n# keep my custom configuration\n")
        root.resolve("data/rime/upgrade-preservation.user.txt").writeText("保留我的自定义词条\n")
        val saved = prefs.edit().putString("setting", "keep-my-setting")
        paths.forEach { saved.putString(it, digest(root.resolve(it))) }
        assertTrue(saved.commit())
    }

    private fun verifyUserData() {
        assertEquals("keep-my-setting", prefs.getString("setting", null))
        paths.forEach { assertEquals("User data changed: $it", prefs.getString(it, null), digest(root.resolve(it))) }
        assertTrue(DataManager.dataDir.resolve("usr/share/anthy/anthy.dic").isFile)
        assertTrue(DataManager.dataDir.resolve("usr/share/rime-data/cn_dicts/base.dict.yaml").isFile)
    }

    @Test
    fun verifyLiteUpgrade() {
        sync()
        verifyUserData()
        assertFalse(DataManager.dataDir.resolve("usr/share/libime/zh_CN.lm").exists())
        assertFalse(DataManager.dataDir.resolve("usr/share/fcitx5/table/wbpy.main.dict").exists())
        assertFalse(DataManager.dataDir.resolve("usr/share/fcitx5/inputmethod/pinyin.conf").exists())
        assertFalse(DataManager.dataDir.resolve("usr/share/fcitx5/inputmethod/wbpy.conf").exists())
    }

    @Test
    fun verifyFullRestore() {
        sync()
        verifyUserData()
        assertTrue(DataManager.dataDir.resolve("usr/share/libime/zh_CN.lm").isFile)
        assertTrue(DataManager.dataDir.resolve("usr/share/fcitx5/table/wbpy.main.dict").isFile)
        assertTrue(DataManager.dataDir.resolve("usr/share/fcitx5/inputmethod/pinyin.conf").isFile)
    }
}
