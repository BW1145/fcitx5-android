/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.os.Looper
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.fcitx.fcitx5.android.data.SavedContentStore
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.clipboard.ContentSearchResults
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FcitxApplication::class,shadows=[AndroidAtomicFileShadow::class])
class ContentSearchResultsTest {
    @Test fun repeatedQueryKeepsRowsClickableAndChangedQueryRemovesStaleRows() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val entry=SavedContentStore.put(null,"needle-result","chosen text")
        val chosen=mutableListOf<String>()
        val results=ContentSearchResults(RuntimeEnvironment.getApplication(),scope,ThemeManager.DefaultTheme) { chosen+=it }
        fun awaitRows() {
            val deadline=System.nanoTime()+5_000_000_000L
            while(results.root.childCount==0 && System.nanoTime()<deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
                Thread.sleep(10)
            }
            assertTrue("Background search publishes results",results.root.childCount>0)
        }
        try {
            results.search("needle",true)
            awaitRows()
            val row=results.root.getChildAt(0) as TextView
            assertEquals(entry.title,row.text.toString())
            // Engine preedit events can repeat an unchanged query after rows appear.
            results.search("needle",true)
            row.performClick()
            assertEquals(listOf(entry.text),chosen)
            assertSame(row,results.root.getChildAt(0))

            results.search("missing",true)
            assertEquals("Old results disappear before the next query completes",0,results.root.childCount)
            row.performClick()
            assertEquals("A stale row cannot insert old content",listOf(entry.text),chosen)
            awaitRows()
            assertEquals(RuntimeEnvironment.getApplication().getString(R.string.search_empty),
                (results.root.getChildAt(0) as TextView).text.toString())

            results.search("needle",true)
            awaitRows()
            val reopened=results.root.getChildAt(0)
            results.close()
            reopened.performClick()
            assertEquals("Closing invalidates pending result actions",listOf(entry.text),chosen)
        } finally {
            results.close();scope.cancel();SavedContentStore.delete(entry.id)
        }
    }
}
