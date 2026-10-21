/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.os.Looper
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    @Test fun visibleRowsStayClickableDuringRefreshAndCloseEndsTheirSession() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val entry=SavedContentStore.put(null,"needle-result","chosen text")
        val chosen=mutableListOf<String>()
        val results=ContentSearchResults(RuntimeEnvironment.getApplication(),scope,ThemeManager.DefaultTheme) { chosen+=it }
        fun layout() {
            results.root.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY))
            results.root.layout(0,0,500,500)
        }
        fun awaitSearch() {
            val deadline=System.nanoTime()+5_000_000_000L
            fun pending()=scope.coroutineContext[Job]!!.children.any { it.isActive }
            while(pending() && System.nanoTime()<deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
                Thread.sleep(10)
            }
            assertFalse("Background search finishes",pending())
        }
        fun awaitResult(text:String) {
            awaitSearch()
            val deadline=System.nanoTime()+5_000_000_000L
            while(System.nanoTime()<deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                layout()
                if((results.root.getChildAt(0) as? TextView)?.text?.toString()==text) break
                Thread.sleep(5)
            }
            assertEquals(text,(results.root.getChildAt(0) as? TextView)?.text?.toString())
        }
        try {
            results.search("needle",true)
            awaitResult(entry.title)
            val row=results.root.getChildAt(0) as TextView
            assertEquals(entry.title,row.text.toString())
            // Engine preedit events can repeat an unchanged query after rows appear.
            results.search("needle",true)
            row.performClick()
            assertEquals(listOf(entry.text),chosen)
            assertSame(row,results.root.getChildAt(0))

            // A different query may begin after touch down on a visible row.
            results.search("need",true)
            row.performClick()
            assertEquals("Visible results remain clickable during a background refresh",listOf(entry.text,entry.text),chosen)
            awaitSearch()
            assertSame("Unchanged results keep the same touch target",row,results.root.getChildAt(0))

            results.search("missing",true)
            awaitResult(RuntimeEnvironment.getApplication().getString(R.string.search_empty))

            results.search("needle",true)
            awaitResult(entry.title)
            val reopened=results.root.getChildAt(0)
            results.close()
            reopened.performClick()
            assertEquals("Closing invalidates pending result actions",listOf(entry.text,entry.text),chosen)
        } finally {
            results.close();scope.cancel();SavedContentStore.delete(entry.id)
        }
    }
}
