/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Looper
import android.os.UserManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.rime.RimeFuzzyConfig
import org.fcitx.fcitx5.android.data.rime.RimePersonalDictionary
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.TwinSeekBarPreference
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardPreferenceSections
import org.fcitx.fcitx5.android.ui.main.settings.behavior.TypingSettingsFragment
import org.fcitx.fcitx5.android.input.keyboard.typing.KeyCell
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchProfileStore
import org.fcitx.fcitx5.android.input.keyboard.typing.TapEvidence
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardPreferenceSectionsTest {
    private var previousApplication: Any? = null
    private var previousPrefs: Any? = null
    private lateinit var stored: SharedPreferences

    @Before fun prepare() {
        val application = RuntimeEnvironment.getApplication()
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            previousApplication = get(null)
            set(null, app)
        }
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            previousPrefs = get(null)
            set(null, null)
        }
        stored = PreferenceManager.getDefaultSharedPreferences(application)
        stored.edit().clear().commit()
        AppPrefs.init(stored)
    }

    @After fun restoreApplication() {
        val listener = AppPrefs::class.java.getDeclaredField("onSharedPreferenceChangeListener")
            .apply { isAccessible = true }.get(AppPrefs.getInstance())
            as SharedPreferences.OnSharedPreferenceChangeListener
        stored.unregisterOnSharedPreferenceChangeListener(listener)
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousPrefs)
        }
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousApplication)
        }
    }

    private fun activity() = Robolectric.buildActivity(AppCompatActivity::class.java).also {
        it.get().setTheme(R.style.Theme_FcitxAppTheme)
    }.setup()

    private fun controls(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).flatMap { index ->
            val preference = group.getPreference(index)
            if (preference is PreferenceGroup) controls(preference) else listOf(preference)
        }

    private fun screen(context: Context, name: String, prefs: AppPrefs.Keyboard): PreferenceScreen =
        PreferenceManager(context).apply { sharedPreferencesName = name }
            .createPreferenceScreen(context).also { prefs.createUi(it) }

    @Test fun allOriginalControlsAppearExactlyOnceAndKeepTheirOriginalObjectsAcrossTheThreePages() {
        val controller = activity()
        try {
            val context = controller.get()
            val stored = context.getSharedPreferences("section-coverage", Context.MODE_PRIVATE)
            stored.edit().clear().commit()
            val prefs = AppPrefs(stored).keyboard
            val originalKeys = controls(screen(context, "section-coverage", prefs)).map { it.key }.toSet()
            val allVisibleKeys = mutableListOf<String>()
            KeyboardPreferenceSections.Page.entries.forEach { page ->
                val screen = screen(context, "section-coverage", prefs)
                val originalObjects = controls(screen).associateBy { it.key }
                val valuesBefore = stored.all.toMap()
                KeyboardPreferenceSections.arrange(screen, prefs, page)
                controls(screen).forEach { preference ->
                    allVisibleKeys += preference.key
                    assertSame("${preference.key} must retain its dialog and binding",
                        originalObjects[preference.key], preference)
                    assertNotSame("Controls should be in a named section", screen, preference.parent)
                }
                assertEquals("Opening a page cannot change a saved setting", valuesBefore, stored.all)
            }
            assertEquals("No original keyboard setting may disappear", originalKeys, allVisibleKeys.toSet())
            assertEquals("No setting may be repeated on two pages", allVisibleKeys.size, allVisibleKeys.toSet().size)
            assertTrue(allVisibleKeys.contains("keep_keyboard_letters_uppercase"))
            assertTrue(allVisibleKeys.contains("button_vibration_press_milliseconds"))
            assertTrue(allVisibleKeys.contains("expanded_candidate_grid_span_count_portrait"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun unknownFutureControlsRemainUsableOnTheKeyboardPage() {
        val controller = activity()
        try {
            val context = controller.get()
            val stored = context.getSharedPreferences("section-future", Context.MODE_PRIVATE)
            val prefs = AppPrefs(stored).keyboard
            var clicks = 0
            KeyboardPreferenceSections.Page.entries.forEach { page ->
                val screen = screen(context, "section-future", prefs)
                val future = Preference(context).apply {
                    key = "future_keyboard_option"
                    title = "Future keyboard option"
                    setOnPreferenceClickListener { clicks++; true }
                }
                screen.addPreference(future)
                KeyboardPreferenceSections.arrange(screen, prefs, page)
                val retained = screen.findPreference<Preference>(future.key)
                if (page == KeyboardPreferenceSections.Page.Keyboard) {
                    assertSame(future, retained)
                    assertEquals("keyboard_other", retained!!.parent!!.key)
                    retained.performClick()
                } else {
                    assertNull("Future controls should not be duplicated", retained)
                }
            }
            assertEquals(1, clicks)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun allTwinControlsRetainBothStoredValuesAndTheirDialogsStillPersistAfterMoving() {
        val controller = activity()
        try {
            val context = controller.get()
            val stored = context.getSharedPreferences("section-twins", Context.MODE_PRIVATE)
            stored.edit().clear()
                .putInt("keyboard_height_percent", 41).putInt("keyboard_height_percent_landscape", 53)
                .putInt("keyboard_side_padding", 12).putInt("keyboard_side_padding_landscape", 18)
                .putInt("keyboard_bottom_padding", 9).putInt("keyboard_bottom_padding_landscape", 13)
                .putInt("button_vibration_press_milliseconds", 22).putInt("button_vibration_long_press_milliseconds", 35)
                .putInt("button_vibration_press_amplitude", 90).putInt("button_vibration_long_press_amplitude", 120)
                .putInt("expanded_candidate_grid_span_count_portrait", 7)
                .putInt("expanded_candidate_grid_span_count_landscape", 9).commit()
            val prefs = AppPrefs(stored).keyboard
            val found = mutableSetOf<String>()
            KeyboardPreferenceSections.Page.entries.forEach { page ->
                val screen = screen(context, "section-twins", prefs)
                val original = controls(screen).filterIsInstance<TwinSeekBarPreference>().associateBy { it.key }
                val before = stored.all.toMap()
                KeyboardPreferenceSections.arrange(screen, prefs, page)
                controls(screen).filterIsInstance<TwinSeekBarPreference>().forEach { control ->
                    found += control.key
                    assertSame(original[control.key], control)
                    assertEquals(stored.getInt(control.key, -1), control.value)
                    assertEquals(stored.getInt(control.secondaryKey, -1), control.secondaryValue)
                }
                assertEquals(before, stored.all)
                if (page == KeyboardPreferenceSections.Page.Keyboard) {
                    val height = screen.findPreference<TwinSeekBarPreference>(prefs.keyboardHeightPercent.key)!!
                    height.performClick()
                    val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                    shadowOf(Looper.getMainLooper()).idle()
                    fun bars(view: View): List<SeekBar> = when (view) {
                        is SeekBar -> listOf(view)
                        is ViewGroup -> (0 until view.childCount).flatMap { bars(view.getChildAt(it)) }
                        else -> emptyList()
                    }
                    val sliders = bars(dialog.window!!.decorView)
                    assertEquals(2, sliders.size)
                    sliders[0].progress = 32
                    sliders[1].progress = 46
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    // AlertDialog dispatches its button callback through the main looper.
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(42, prefs.keyboardHeightPercent.getValue())
                    assertEquals(56, prefs.keyboardHeightPercentLandscape.getValue())
                }
            }
            assertEquals("All six portrait/landscape or press/hold controls must survive", 6, found.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Only the native boundary is substituted; production fragments, clicks and file actions run. */
    private class RecordingEngine : FcitxConnection {
        val deployments = CopyOnWriteArrayList<Int>()
        private val deploy = Action(71, false, false, false, "fcitx-rime-deploy", "", "Deploy", "", null)
        private val api = Proxy.newProxyInstance(FcitxAPI::class.java.classLoader,
            arrayOf(FcitxAPI::class.java)) { _, method, args ->
            when (method.name) {
                "getInputMethodEntryCached" -> InputMethodEntry("rime", "Chinese", "", "", "", "zh", "rime", true)
                "statusArea" -> arrayOf(deploy)
                "activateAction" -> { deployments += args!![0] as Int; Unit }
                else -> error("Unexpected native call: ${method.name}")
            }
        } as FcitxAPI
        override val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = runBlocking { block(api) }
        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = block(api)
        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) { runBlocking { block(api) } }
    }

    private fun withTypingFragment(block: (AppCompatActivity, TypingSettingsFragment, RecordingEngine, File) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val clients = FcitxDaemon::class.java.getDeclaredField("clients").run {
            isAccessible = true
            get(FcitxDaemon) as MutableMap<String, FcitxConnection>
        }
        val previous = clients.toMap()
        val engine = RecordingEngine()
        // The sentinel prevents MainViewModel cleanup from trying to stop a nonexistent native engine.
        clients[MainViewModel::class.java.name] = engine
        clients["settings-test-sentinel"] = engine
        val controller = activity()
        val application = RuntimeEnvironment.getApplication()
        val directory = (application.getExternalFilesDir(null) ?: application.filesDir).resolve("data/rime")
        directory.mkdirs()
        try {
            val host = FrameLayout(controller.get()).apply { id = View.generateViewId() }
            controller.get().setContentView(host)
            val fragment = TypingSettingsFragment()
            controller.get().supportFragmentManager.beginTransaction().add(host.id, fragment).commitNow()
            block(controller.get(), fragment, engine, directory)
        } finally {
            controller.pause().stop().destroy()
            clients.clear()
            clients.putAll(previous)
            engine.lifecycleScope.cancel()
        }
    }

    private fun awaitUi(message: String, finished: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!finished() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(message, finished())
    }

    @Test fun clickingGroupedApplyWritesTheRealFuzzyConfigurationAndDeploysIt() {
        withTypingFragment { _, fragment, engine, directory ->
            val prefs = AppPrefs.getInstance().keyboard
            prefs.rimeFuzzyNl.setValue(true)
            prefs.rimeFuzzyZh.setValue(false)
            prefs.rimeFuzzyAng.setValue(true)
            val apply = fragment.findPreference<Preference>(KeyboardPreferenceSections.APPLY_TYPING_KEY)!!
            assertEquals("typing_fuzzy", apply.parent!!.key)
            apply.performClick()
            awaitUi("The visible Apply row must reach the engine deploy action") { engine.deployments.size == 1 }
            assertEquals(listOf(71), engine.deployments.toList())
            assertEquals(RimeFuzzyConfig.render(true, false, true), directory.resolve("xuancai_mobile.yaml").readText())
        }
    }

    @Test fun pinyinTouchCorrectionIsOptInAndItsVisibleSwitchDoesNotEnableDiagnosticsOrDeployRime() {
        withTypingFragment { _, fragment, engine, _ ->
            val prefs = AppPrefs.getInstance().keyboard
            assertFalse(prefs.pinyinTouchCorrection.getValue())
            val correction = fragment.findPreference<Preference>(prefs.pinyinTouchCorrection.key)!!
            assertSame("The visible switch and runtime setting must share their backing store",
                prefs.pinyinTouchCorrection.sharedPreferences, correction.sharedPreferences)
            assertEquals("typing_touch_correction", correction.parent!!.key)
            assertEquals(fragment.getString(R.string.pinyin_touch_correction_summary), correction.summary)
            correction.performClick()
            assertTrue("The visible switch must persist the opt-in", prefs.pinyinTouchCorrection.getValue())
            assertFalse("Correction must not start collecting diagnostic logs", prefs.touchDiagnosticLogging.getValue())
            assertFalse("Correction must not enable long-hold boundary adjustment", prefs.touchBoundarySettling.getValue())
            assertTrue("Confirmed calibration is applied by default; this does not start learning",
                prefs.pinyinTouchPersonalization.getValue())
            val learning = fragment.findPreference<Preference>(prefs.pinyinTouchPersonalization.key)!!
            assertEquals("typing_touch_correction", learning.parent!!.key)
            learning.performClick()
            assertFalse(prefs.pinyinTouchPersonalization.getValue())
            assertEquals("typing_touch_correction", fragment.findPreference<Preference>(
                KeyboardPreferenceSections.PINYIN_TOUCH_PROFILE_CLEAR_KEY)!!.parent!!.key)
            assertTrue("Touch correction must not trigger a Rime rebuild", engine.deployments.isEmpty())
            correction.performClick()
            assertFalse(prefs.pinyinTouchCorrection.getValue())
            learning.performClick()
            assertTrue(prefs.pinyinTouchPersonalization.getValue())
        }
    }

    @Test fun nextWordSuggestionsAreEnabledByDefaultAndTheirVisibleSwitchPersistsIndependently() {
        withTypingFragment { _, fragment, engine, _ ->
            val prefs = AppPrefs.getInstance().keyboard
            val switch = fragment.findPreference<Preference>(prefs.localNextWordPrediction.key)!!
            assertTrue(prefs.localNextWordPrediction.getValue())
            assertSame(prefs.localNextWordPrediction.sharedPreferences, switch.sharedPreferences)
            assertEquals("typing_next_word_prediction", switch.parent!!.key)
            assertEquals("typing_next_word_prediction", fragment.preferenceScreen.getPreference(0).key)
            val otherSettings = stored.all.filterKeys { it != prefs.localNextWordPrediction.key }
            switch.performClick()
            assertFalse(prefs.localNextWordPrediction.getValue())
            assertFalse("Recreating preferences must preserve a saved opt-out",
                AppPrefs(stored).keyboard.localNextWordPrediction.getValue())
            assertEquals(otherSettings, stored.all.filterKeys { it != prefs.localNextWordPrediction.key })
            assertTrue("Toggling suggestions must not deploy Rime", engine.deployments.isEmpty())
            switch.performClick()
            assertTrue(prefs.localNextWordPrediction.getValue())
        }
    }

    @Test fun suggestionPrivacyHelpOpensWithoutChangingPredictionOrLearningSettings() {
        withTypingFragment { activity, fragment, engine, _ ->
            val before = stored.all.toMap()
            val help = fragment.findPreference<Preference>(KeyboardPreferenceSections.NEXT_WORD_PREDICTION_HELP_KEY)!!
            assertEquals("typing_next_word_prediction", help.parent!!.key)
            help.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val message = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!
            assertEquals(activity.getString(R.string.next_word_prediction_privacy_message), message.text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals("Reading help must not enable diagnostics or create learning settings", before, stored.all)
            assertTrue(engine.deployments.isEmpty())
        }
    }

    @Test fun clickingTypingHabitResetClearsTheActualLocalProfileWithoutChangingSettings() {
        withTypingFragment { activity, fragment, engine, _ ->
            shadowOf(activity.application.getSystemService(UserManager::class.java)).setUserUnlocked(true)
            PinyinTouchProfileStore.clear(activity)
            val store = PinyinTouchProfileStore(activity)
            val cells = listOf(KeyCell('n', 0f, 0f, 100f, 100f))
            repeat(8) { assertTrue(store.observe(cells, TapEvidence('n', 60f, 55f, 1f), 'n')) }
            assertEquals(8, store.confirmedSampleCount(cells, 1f, 'n'))
            fragment.findPreference<Preference>(KeyboardPreferenceSections.PINYIN_TOUCH_PROFILE_CLEAR_KEY)!!
                .performClick()
            awaitUi("The visible reset must clear the local profile") {
                store.confirmedSampleCount(cells, 1f, 'n') == 0
            }
            assertTrue(store.offsets(cells, 1f).isEmpty())
            assertFalse(AppPrefs.getInstance().keyboard.pinyinTouchCorrection.getValue())
            assertTrue(AppPrefs.getInstance().keyboard.pinyinTouchPersonalization.getValue())
            assertTrue(engine.deployments.isEmpty())
        }
    }

    @Test fun clickingGroupedDictionaryImportOpensThePickerAndMergesItsResultThroughTheOriginalImporter() {
        withTypingFragment { activity, fragment, engine, directory ->
            val dictionary = directory.resolve(RimePersonalDictionary.FILE_NAME)
            dictionary.writeText(RimePersonalDictionary.render(listOf(
                RimePersonalDictionary.Entry("已有", "yi you", 80))))
            val importPreference = fragment.findPreference<Preference>(KeyboardPreferenceSections.DICTIONARY_IMPORT_KEY)!!
            assertEquals("typing_dictionary", importPreference.parent!!.key)
            importPreference.performClick()
            val request = shadowOf(activity).nextStartedActivityForResult
            assertNotNull("The original system file picker must still open", request)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
            assertTrue(request.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.contains("text/tab-separated-values"))
            val uri = Uri.parse("content://axiang-test/personal-dictionary.tsv")
            shadowOf(activity.contentResolver).registerInputStream(uri,
                ByteArrayInputStream("阿祥\ta xiang\t100\n".toByteArray(Charsets.UTF_8)))
            shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
            awaitUi("Selecting a dictionary must run the original merge and deploy") {
                engine.deployments.size == 1 && importPreference.isEnabled
            }
            assertEquals(setOf("已有", "阿祥"), RimePersonalDictionary.readStored(dictionary.readText()).map { it.text }.toSet())
            assertEquals(listOf(71), engine.deployments.toList())
        }
    }
}
