/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.graphics.*
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.fcitx.fcitx5.android.data.theme.ArtThemes
import org.fcitx.fcitx5.android.data.theme.panelDrawable
import org.fcitx.fcitx5.android.input.bar.ui.idle.ButtonsBarUi
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardSettingsFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp-xxhdpi",application=FcitxApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExperienceUiTest {
    @Test fun themeFeedbackDistinguishesPressHoldAndGestureAndRespectsReducedMotion() {
        val app=RuntimeEnvironment.getApplication()
        val ctx=ContextThemeWrapper(app,R.style.Theme_InputViewTheme)
        val prefs=AppPrefs.getInstance().advanced.disableAnimation
        val previous=prefs.getValue()
        prefs.setValue(true)
        val output=File("build/reports/theme-previews").apply { mkdirs() }
        fun capture(view: View): Bitmap = Bitmap.createBitmap(270,210,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        fun hash(bitmap: Bitmap): Int {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return pixels.contentHashCode()
        }
        try {
            ArtThemes.all.forEachIndexed { index,theme ->
                val key=TextKeyView(ctx,theme,KeyDef.Appearance.Text("a",23f))
                key.measure(View.MeasureSpec.makeMeasureSpec(270,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(210,View.MeasureSpec.EXACTLY))
                key.layout(0,0,270,210)
                val sheet=Bitmap.createBitmap(1080,210,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(sheet)
                val hashes=mutableSetOf<Int>()
                for (state in 0..3) {
                    key.isPressed=false; key.isPressed=state>0
                    if(state==2) key.onVisualFeedback!!.invoke(CustomGestureView.Feedback.Hold)
                    if(state==3) key.onVisualFeedback!!.invoke(CustomGestureView.Feedback.Gesture)
                    val image=capture(key);hashes+=hash(image);canvas.drawBitmap(image,state*270f,0f,null);image.recycle()
                }
                assertEquals("Idle, pressed, long press and gesture must all be visually distinct",4,hashes.size)
                key.isPressed=false
                val reset=capture(key)
                assertEquals("With animation disabled release returns immediately to idle",hashes.first(),hash(reset))
                reset.recycle()
                prefs.setValue(false)
                key.isPressed=true;key.isPressed=false
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350))
                val settled=capture(key)
                assertEquals("Release animation settles back to idle",hashes.first(),hash(settled))
                settled.recycle();prefs.setValue(true)
                File(output,"feedback-$index.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG,100,it) }
                sheet.recycle()
            }
        } finally { prefs.setValue(previous) }
    }

    @Test fun renderFourArtKeyboardsWithTheirRealViews() {
        val app=RuntimeEnvironment.getApplication()
        val ctx=ContextThemeWrapper(app,R.style.Theme_InputViewTheme)
        val output=File("build/reports/theme-previews").apply { mkdirs() }
        val hashes=mutableSetOf<Int>()
        ArtThemes.all.forEachIndexed { index,theme ->
            val root=LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL;background=theme.backgroundDrawable() }
            root.addView(ButtonsBarUi(ctx,theme).root.apply { background=theme.panelDrawable(3f) },LinearLayout.LayoutParams(1080,120))
            root.addView(TextView(ctx).apply { text="你好     耍我们玩     输入法";textSize=20f;setTextColor(theme.candidateTextColor)
                setPadding(24,12,24,12);background=theme.panelDrawable(3f) },LinearLayout.LayoutParams(1080,120))
            root.addView(TextKeyboard(ctx,theme),LinearLayout.LayoutParams(1080,650))
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(890,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,890)
            val bitmap=Bitmap.createBitmap(1080,890,Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val pixels=IntArray(1080*890);bitmap.getPixels(pixels,0,1080,0,0,1080,890)
            assertTrue("Rendered keyboard contains ink and materials",pixels.distinct().size>20)
            hashes+=pixels.contentHashCode()
            File(output,"theme-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        assertEquals(4,hashes.size)
    }
    @Test fun groupedKeyboardSettingsKeepWorkingSummaryProviders() {
        val controller=Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            controller.get().setTheme(R.style.Theme_FcitxAppTheme)
            val fragment=KeyboardSettingsFragment()
            controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content,fragment).commitNow()
            assertNotNull(fragment.findPreference<androidx.preference.Preference>("keyboard_height_percent"))
            assertTrue(fragment.findPreference<androidx.preference.Preference>("expanded_candidate_style")!!.summary.toString().contains("按词长排列"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun settingsSearchFindsEverydayTermsAndAssistantTools() {
        val controller=Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            controller.get().setTheme(R.style.Theme_FcitxAppTheme)
            val fragment=org.fcitx.fcitx5.android.ui.main.settings.SettingsSearchFragment()
            controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content,fragment).commitNow()
            val query=fragment.findPreference<androidx.preference.EditTextPreference>("settings_search_query")!!
            query.callChangeListener("键盘往上挪")
            val results=fragment.preferenceScreen.getPreference(1) as androidx.preference.PreferenceCategory
            assertTrue(results.preferenceCount>0)
            assertTrue((0 until results.preferenceCount).any { results.getPreference(it).title.toString().contains("padding",true) || results.getPreference(it).title.toString().contains("边距") })
            query.callChangeListener("API")
            assertTrue((0 until results.preferenceCount).any { results.getPreference(it).title.toString().contains("AI") })
            query.callChangeListener("备份")
            val titles=(0 until results.preferenceCount).map { results.getPreference(it).title.toString() }
            listOf(R.string.import_user_data,R.string.export_user_data,R.string.browse_user_data_dir).forEach {
                assertTrue("Backup search includes the existing user-data action",controller.get().getString(it) in titles)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun experienceDefaultsPreserveAnExplicitCandidateLayout() {
        val prefs=AppPrefs.getInstance().keyboard.expandedCandidateStyle
        val previous=prefs.getValue()
        val root=java.nio.file.Files.createTempDirectory("experience-defaults").toFile()
        try {
            prefs.setValue(org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle.Grid)
            org.fcitx.fcitx5.android.core.PersonalDefaults.installExperienceDefaults(root)
            assertEquals(org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle.Grid,prefs.getValue())
        } finally { prefs.setValue(previous);root.deleteRecursively() }
    }
}
