package org.fcitx.fcitx5.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PersonalPluginContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val mainId = context.packageName
    private val baseId = mainId.removeSuffix(".debug")
    private val suffix = if (mainId.endsWith(".debug")) ".debug" else ""

    private fun pluginId(name: String) = "$baseId.plugin.$name$suffix"

    @Test
    fun matchingNativeAndServicePluginsAreDiscovered() {
        val expected = setOf(pluginId("chewing"), pluginId("clipboard_filter"))
        val detected = DataManager.detectPlugins()
        assertTrue("Missing custom plugins: $detected", detected.loaded.map { it.packageName }.containsAll(expected))
        for (plugin in expected) {
            assertEquals(PackageManager.SIGNATURE_MATCH,
                context.packageManager.checkSignatures(mainId, plugin))
        }
        assertTrue("The service plugin descriptor must retain hasService",
            detected.loaded.any { it.packageName == pluginId("clipboard_filter") && it.hasService })
        assertTrue("Plugin names must exclude the custom application prefix",
            detected.loaded.map { it.name }.containsAll(setOf("chewing", "clipboard_filter")))
    }

    @Test
    fun clipboardPluginBindsBackToItsMatchingIme() {
        val plugin = pluginId("clipboard_filter")
        val intent = Intent("$mainId.plugin.SERVICE").setPackage(plugin)
        val service = context.packageManager.resolveService(intent, 0)
        assertEquals("$mainId.permission.PLUGIN", service?.serviceInfo?.permission)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission("$mainId.permission.IPC", plugin))
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue("The matching plugin service must be bindable", bound)
            assertTrue("Plugin service did not connect", connected.await(10, TimeUnit.SECONDS))
            val deadline = SystemClock.uptimeMillis() + 10_000
            var transformed: String? = null
            while (transformed == null && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    transformed = ClipboardManager.transformer?.invoke("personal plugin round trip")
                }
                if (transformed == null) SystemClock.sleep(25)
            }
            assertEquals("The plugin must register with this IME's IPC service",
                "personal plugin round trip", transformed)
            val target = "https://example.com/a%2Fb?q=rock%26roll#part%23one"
            val redirect = "https://www.google.com/url?q=${Uri.encode(target)}"
            val original = "https://example.com/?q=rock%26roll&utm_source=share#part%23one"
            var cleanedRedirect: String? = null
            var cleanedQuery: String? = null
            instrumentation.runOnMainSync {
                cleanedRedirect = ClipboardManager.transformer?.invoke(redirect)
                cleanedQuery = ClipboardManager.transformer?.invoke(original)
            }
            assertEquals(target, cleanedRedirect)
            assertEquals("https://example.com/?q=rock%26roll#part%23one", cleanedQuery)
        } finally {
            if (bound) context.unbindService(connection)
        }
    }
}
