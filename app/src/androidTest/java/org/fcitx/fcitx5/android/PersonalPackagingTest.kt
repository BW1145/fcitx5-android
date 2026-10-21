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
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.*
import org.fcitx.fcitx5.android.data.ai.AiStore
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon

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

    /** Uses only APIs already present in 1.3.1; run this test against that APK. */
    @Test fun prepareMaintenanceUpgrade()=runBlocking {
        sync()
        AiStore.save(AiStore.Conversation(id="maintenance-upgrade",title="升级验证",
            messages=listOf(AiStore.Message("user","保留旧会话")),draft="保留草稿"))
        AiStore.configure(AiStore.config().copy(fixedPrompt="保留固定提示词"))
        SavedContentStore.put("maintenance-upgrade","升级验证","保留常用内容")
        KeyMacros.set(KeyMacros.Binding("a","up",listOf(KeyMacros.Step("text","保留宏"))))
        ClipboardManager.search("升级剪贴板") // Ensure Room has created its existing schema.
        context.openOrCreateDatabase("clbdb",0,null).use { db ->
            db.execSQL("INSERT INTO clipboard (text,pinned,timestamp,type,deleted,sensitive) VALUES (?,1,?, ?,0,0)",
                arrayOf<Any>("升级剪贴板",System.currentTimeMillis(),"text/plain"))
        }
        val connection=FcitxDaemon.connect("maintenance-upgrade")
        val snapshot=try {
            connection.runOnReady { activate(android.os.Process.myUid(),context.packageName);focus();activateIme("rime");reset() }
            WordLibrary.add(connection,"rime_ice","维护词条","wei hu ci tiao",1000)
            // Seed the legacy TSV using only APIs present in the upgrade fixture.
            root.resolve("data/candidate-preferences.tsv").writeText(
                WordLibrary.Rule("rime","weihucitiao","维护词条","lower").line+"\n",Charsets.UTF_8)
            WordLibrary.snapshot(connection,"升级前输入习惯")
        } finally { FcitxDaemon.disconnect("maintenance-upgrade") }
        val files=listOf("data/ai/settings.json","data/ai/conversations/maintenance-upgrade.json",
            "data/saved-content.json","data/key-macros.json","data/candidate-preferences.tsv")+
            root.resolve("data/input-history/${snapshot.id}").walkTopDown().filter { it.isFile }
                .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        val saved=prefs.edit().putStringSet("maintenance-files",files.toSet())
        files.forEach { saved.putString("maintenance:$it",digest(root.resolve(it))) }
        assertTrue(saved.commit())
    }

    @Test fun verifyMaintenanceUpgrade()=runBlocking {
        sync()
        val files=prefs.getStringSet("maintenance-files",null)!!
        files.forEach { assertEquals("Upgrade changed $it",prefs.getString("maintenance:$it",null),digest(root.resolve(it))) }
        val conversation=AiStore.all().first { it.id=="maintenance-upgrade" }
        assertEquals("保留草稿",conversation.draft)
        assertEquals("保留旧会话",conversation.messages.single().text)
        assertEquals("保留固定提示词",AiStore.config().fixedPrompt)
        assertTrue(SavedContentStore.load().any { it.text=="保留常用内容" })
        assertTrue(KeyMacros.load().any { it.key=="a" && it.steps.any { step -> step.text=="保留宏" } })
        assertTrue(ClipboardManager.search("升级剪贴板").any { it.pinned })
        assertTrue(WordLibrary.snapshots().any { it.name=="升级前输入习惯" })
        val connection=FcitxDaemon.connect("maintenance-upgrade")
        try { assertTrue(WordLibrary.words(connection).any { it.text=="维护词条" }) }
        finally { FcitxDaemon.disconnect("maintenance-upgrade") }
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
        prefs.getStringSet("maintenance-files",null).orEmpty().forEach {
            assertEquals("Maintenance data changed: $it",prefs.getString("maintenance:$it",null),digest(root.resolve(it)))
        }
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
