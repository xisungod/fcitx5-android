/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.RimeTouchProbe
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.typing.KeyCell
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTapEvidence
import org.fcitx.fcitx5.android.input.keyboard.typing.TapEvidence
import org.json.JSONObject
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TypingTestSessionTest {
    private lateinit var context: Application
    private lateinit var stored: SharedPreferences
    private var previousApplication: Any? = null
    private var previousPrefs: Any? = null
    private val prompt get() = TypingTestPrompts.all.first()

    @Before fun prepare(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
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
        stored = context.getSharedPreferences("typing-test-session-test", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        AppPrefs.init(stored)
        resetSession()
    }

    @After fun clean(): Unit = runBlocking {
        resetSession()
        val preferenceListener = AppPrefs::class.java.getDeclaredField(
            "onSharedPreferenceChangeListener").apply { isAccessible = true }
            .get(AppPrefs.getInstance()) as SharedPreferences.OnSharedPreferenceChangeListener
        stored.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousPrefs)
        }
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousApplication)
        }
        Unit
    }

    private suspend fun resetSession() {
        // Start with no results, so abort cannot schedule another completed report.
        TypingTestSession.start(context, 5, FIELD_ID)
        TypingTestSession.abort()
        TypingTestSession.revokeEditor()
        TypingTestSession.clearReports()
    }

    private fun editor() = EditorInfo().apply {
        packageName = BuildConfig.APPLICATION_ID
        fieldId = FIELD_ID
        inputType = InputType.TYPE_CLASS_TEXT
        hintText = "private editor hint never exported"
    }

    private fun start(info: EditorInfo = editor()): EditorInfo {
        TypingTestSession.start(context, 5, FIELD_ID)
        TypingTestSession.attachEditor(info)
        TypingTestSession.setActive(true)
        return info
    }

    private fun finish(
        ticket: TypingTestKeyTicket,
        raw: String?,
        candidates: List<String>? = listOf(prompt.text),
        schemaSupported: Boolean = true
    ) {
        TypingTestSession.startKey(ticket)
        val finished = (ticket.startNanos ?: ticket.enqueueNanos) + 2_000_000L
        TypingTestSession.finishKey(ticket, finished, raw, candidates, schemaSupported, 37L)
    }

    private fun type(info: EditorInfo, spelling: String = prompt.pinyin,
                     candidates: List<String> = listOf(prompt.text), prefix: String = "",
                     schemaSupported: Boolean = true) {
        var raw = prefix
        spelling.forEach { letter ->
            val ticket = TypingTestSession.beginKey(info, letter, null)!!
            raw += letter
            finish(ticket, raw, candidates, schemaSupported)
        }
    }

    private fun commitAndComplete(info: EditorInfo, text: String = prompt.text): TypingTestTrialResult {
        TypingTestSession.observeCommit(info, text)
        TypingTestSession.completePhrase(text)
        assertEquals(TypingTestPhase.Completed, TypingTestSession.state.value.phase)
        return TypingTestSession.state.value.results.last()
    }

    @Test fun defaultInactiveDoesNotCaptureKeysCommitsOrEnableGlobalDiagnostics() {
        val info = editor()
        TypingTestSession.attachEditor(info)
        assertFalse(TypingTestSession.isEligibleEditor(info))
        assertNull(TypingTestSession.beginKey(info, 'n', null))
        TypingTestSession.observeCommit(info, "secret text")
        assertNull(TypingTestSession.exportReport())
        assertFalse(AppPrefs.getInstance().keyboard.touchDiagnosticLogging.getValue())
        assertTrue(AppPrefs.getInstance().keyboard.pinyinTouchPersonalization.getValue())
        assertTrue(TypingTestSession.confirmedCalibrationSamples().isEmpty())
    }

    @Test fun startingScreenDoesNotArmCollectionBeforeForegroundActivation() {
        val info = editor()
        TypingTestSession.start(context, 5, FIELD_ID)
        TypingTestSession.attachEditor(info)
        assertFalse(TypingTestSession.isEligibleEditor(info))
        assertNull(TypingTestSession.beginKey(info, 'n', null))
        TypingTestSession.setActive(true)
        assertTrue(TypingTestSession.isEligibleEditor(info))
    }

    @Test fun onlyOwnExactTestFieldIsEligible() {
        for (info in listOf(editor().apply { packageName = "other.application" },
            editor().apply { fieldId = FIELD_ID + 1 }, editor().apply { fieldId = 0 },
            editor().apply { inputType = InputType.TYPE_NULL })) {
            start(info)
            assertFalse(TypingTestSession.isEligibleEditor(info))
            assertNull(TypingTestSession.beginKey(info, 'n', null))
        }
        val valid = start()
        assertTrue(TypingTestSession.isEligibleEditor(valid))
        assertFalse(TypingTestSession.isEligibleEditor(null))
    }

    @Test fun passwordsAndNoLearningFlagFailClosedEvenInKnownTestField() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI)) {
            val info = start(editor().apply { inputType = InputType.TYPE_CLASS_TEXT or variation })
            assertNull(TypingTestSession.beginKey(info, 'n', null))
        }
        val info = start(editor().apply { imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING })
        assertNull(TypingTestSession.beginKey(info, 'n', null))
    }

    @Test fun equalMetadataCannotReplaceBoundEditorObjectIdentity() {
        val first = start()
        val replacement = editor()
        assertTrue(TypingTestSession.isEligibleEditor(first))
        assertFalse(TypingTestSession.isEligibleEditor(replacement))
        TypingTestSession.attachEditor(replacement)
        assertFalse(TypingTestSession.isEligibleEditor(first))
        assertTrue(TypingTestSession.isEligibleEditor(replacement))
    }

    @Test fun editorMutatedSensitiveAfterBindingImmediatelyStopsCollection() {
        val info = start()
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertNull(TypingTestSession.beginKey(info, 'n', null))
        TypingTestSession.observeCommit(info, "secret password")
        assertNull(TypingTestSession.exportReport())
    }

    @Test fun revokedQueuedTicketCannotPublishRawCandidatesOrTimingAfterReattach() {
        val info = start()
        val stale = TypingTestSession.beginKey(info, 'n', null)!!
        TypingTestSession.revokeEditor()
        TypingTestSession.attachEditor(info)
        finish(stale, prompt.pinyin, listOf("stale candidate"))
        val result = commitAndComplete(info)
        assertNull(result.finalInputPinyin)
        assertEquals(0, result.latency.sampleCount)
        assertEquals(TypingTestInputKind.UNKNOWN, result.inputKind)
    }

    @Test fun editorRebindInvalidatesPreviouslyQueuedWork() {
        val info = start()
        val stale = TypingTestSession.beginKey(info, 'n', null)!!
        val replacement = editor()
        TypingTestSession.attachEditor(replacement)
        finish(stale, prompt.pinyin, listOf("stale candidate"))
        val result = commitAndComplete(replacement)
        assertNull(result.finalInputPinyin)
        assertEquals(0, result.latency.sampleCount)
    }

    @Test fun backgroundPauseInvalidatesWorkEvenWhenSameFieldResumes() {
        val info = start()
        val stale = TypingTestSession.beginKey(info, 'n', null)!!
        TypingTestSession.setActive(false)
        assertFalse(TypingTestSession.isEligibleEditor(info))
        TypingTestSession.setActive(true)
        finish(stale, prompt.pinyin)
        val result = commitAndComplete(info)
        assertNull(result.finalInputPinyin)
        assertEquals(0, result.latency.sampleCount)
    }

    @Test fun previousTrialCompletionCannotDrainNewTrialPendingCounter() {
        val info = start()
        val stale = TypingTestSession.beginKey(info, 'n', null)!!
        start(info)
        val current = TypingTestSession.beginKey(info, 'n', null)!!
        finish(stale, prompt.pinyin)
        TypingTestSession.completePhrase("")
        assertEquals(TypingTestPhase.Typing, TypingTestSession.state.value.phase)
        assertNotNull(TypingTestSession.state.value.failure)
        finish(current, "n")
        type(info, prompt.pinyin.drop(1), prefix = "n")
        val result = commitAndComplete(info)
        assertEquals(prompt.pinyin.length, result.latency.sampleCount)
        assertEquals(TypingTestInputKind.FULL_PINYIN, result.inputKind)
    }

    @Test fun duplicateFinishCannotCountTwiceOrOverwriteCandidateSnapshot() {
        val info = start()
        val first = TypingTestSession.beginKey(info, 'n', null)!!
        finish(first, "n")
        finish(first, "xxxxxx", listOf("duplicate stale candidate"))
        type(info, prompt.pinyin.drop(1), prefix = "n")
        val result = commitAndComplete(info)
        assertEquals(prompt.pinyin.length, result.letterKeyCount)
        assertEquals(prompt.pinyin.length, result.latency.sampleCount)
        assertEquals(prompt.pinyin, result.finalInputPinyin)
        assertEquals(TypingTestFraction(1, 1), result.top1HitRate)
    }

    @Test fun completionIsRejectedUntilPendingNativeKeyFinishes() {
        val info = start()
        val ticket = TypingTestSession.beginKey(info, 'n', null)!!
        TypingTestSession.completePhrase("")
        assertEquals(TypingTestPhase.Typing, TypingTestSession.state.value.phase)
        assertTrue(TypingTestSession.state.value.results.isEmpty())
        finish(ticket, "n")
        type(info, prompt.pinyin.drop(1), prefix = "n")
        commitAndComplete(info)
        assertNull(TypingTestSession.state.value.failure)
    }

    @Test fun wrongFullRawIsScoredMissAndChineseCorrectionDoesNotRewriteIt() {
        val info = start()
        type(info, "nuhaoa", listOf("怒号啊", prompt.text))
        val result = commitAndComplete(info)
        assertTrue(result.targetCompleted)
        assertEquals("nuhaoa", result.firstAttemptPinyin)
        assertEquals("nuhaoa", result.finalInputPinyin)
        assertEquals(TypingTestFraction(1, prompt.pinyin.length), result.rawEditRate)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(1, 1), result.top3HitRate)
        assertTrue(result.calibrationSamples.isEmpty())
    }

    @Test fun emptyCoherentCandidatesAreMissesRatherThanMissingDenominators() {
        val info = start()
        type(info, candidates = emptyList())
        val result = commitAndComplete(info)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 1), result.top3HitRate)
    }

    @Test fun partialRawCandidateSnapshotDoesNotBecomeFullPhraseTruth() {
        val info = start()
        val ticket = TypingTestSession.beginKey(info, 'n', null)!!
        finish(ticket, "n", listOf(prompt.text))
        val result = commitAndComplete(info)
        assertNull(result.top1HitRate.value)
        assertNull(result.rawEditRate.value)
    }

    @Test fun lateSnapshotsAfterCommitCannotTurnOriginalMissIntoHit() {
        val info = start()
        type(info, candidates = listOf("你好呀"))
        TypingTestSession.observeCommit(info, prompt.text)
        val later = TypingTestSession.beginKey(info, 'n', null)!!
        finish(later, prompt.pinyin, listOf(prompt.text))
        TypingTestSession.completePhrase(prompt.text)
        val result = TypingTestSession.state.value.results.single()
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 1), result.top3HitRate)
    }

    @Test fun earlyBackspacePreservesPrefixAndDoesNotInventUntypedSuffixErrors() {
        val info = start()
        type(info, "ni")
        val erase = TypingTestSession.beginKey(info, null, null, backspace = true)!!
        finish(erase, "n")
        type(info, "ihaoa", prefix = "n")
        val result = commitAndComplete(info)
        assertEquals("ni", result.firstAttemptPinyin)
        assertEquals(prompt.pinyin, result.finalInputPinyin)
        assertNull(result.rawEditRate.value)
        assertNull(result.adjacentSubstitutionRate.value)
        assertTrue("incomplete_first_attempt" in result.unscoredReasons)
        assertEquals(TypingTestFraction(1, 7), result.backspaceRate)
        assertEquals(TypingTestFraction(0, 0), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 0), result.top3HitRate)
        assertTrue("no_coherent_full_phrase_candidate_snapshot" in result.unscoredReasons)
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertFalse(row.getBoolean("first_attempt_complete"))
        assertTrue(row.isNull("candidate_snapshot"))
        assertTrue(row.getJSONArray("unscored_reasons").toString()
            .contains("no_coherent_full_phrase_candidate_snapshot"))
    }

    @Test fun fullBackspaceAndCorrectRetypeCannotTurnInitialCandidateMissIntoHit() {
        val info = start()
        val original = "nuhaoa"
        type(info, original, listOf("怒号啊"))
        for (length in original.length downTo 1) {
            val erase = TypingTestSession.beginKey(info, null, null, backspace = true)!!
            finish(erase, original.take(length - 1))
        }
        type(info)
        val event = FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(prompt.text, -1))
        TypingTestSession.prepareNativeCommit(event)
        TypingTestSession.observeCommit(info, event)
        TypingTestSession.completePhrase(prompt.text)
        val result = TypingTestSession.state.value.results.single()
        assertTrue(result.targetCompleted)
        assertEquals(original, result.firstAttemptPinyin)
        assertEquals(prompt.pinyin, result.finalInputPinyin)
        assertEquals(TypingTestFraction(1, prompt.pinyin.length), result.rawEditRate)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 1), result.top3HitRate)
        assertEquals(TypingTestFraction(original.length, original.length + prompt.pinyin.length),
            result.backspaceRate)
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertEquals(original, row.getString("initial_candidate_pinyin"))
        assertEquals(original, row.getJSONObject("candidate_snapshot").getString("raw"))
        assertEquals(prompt.pinyin, row.getString("final_pinyin"))
    }

    @Test fun unsupportedSchemaCannotReceivePinyinTruthButRetainsMeasuredTiming() {
        val info = start()
        type(info, schemaSupported = false)
        val result = commitAndComplete(info)
        assertEquals(TypingTestInputKind.UNKNOWN, result.inputKind)
        assertNull(result.top1HitRate.value)
        assertNull(result.rawEditRate.value)
        assertEquals(prompt.pinyin.length, result.latency.sampleCount)
    }

    @Test fun changingCorrectionSettingsDuringTrialExcludesMixedConfigurationScores() {
        val info = start()
        type(info, "n")
        val pref = AppPrefs.getInstance().keyboard.pinyinTouchAlternatives
        pref.setValue(!pref.getValue())
        type(info, prompt.pinyin.drop(1), prefix = "n")
        val result = commitAndComplete(info)
        assertEquals(TypingTestInputKind.UNKNOWN, result.inputKind)
        assertNull(result.top1HitRate.value)
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertTrue(row.getJSONArray("session_exclusions").toString()
            .contains("settings_changed_during_trial"))
    }

    @Test fun unsupportedMarksFromOtherEditorCannotContaminateValidTrial() {
        val info = start()
        TypingTestSession.markUnsupported(editor(), "foreign editor event")
        type(info)
        val result = commitAndComplete(info)
        assertEquals(TypingTestInputKind.FULL_PINYIN, result.inputKind)
        assertEquals(TypingTestFraction(1, 1), result.top1HitRate)
    }

    @Test fun externallyEditedCommittedTextCannotBecomeCalibrationTruth() {
        val info = start()
        type(info)
        TypingTestSession.observeCommit(info, prompt.text)
        TypingTestSession.completePhrase("你好呀")
        val result = TypingTestSession.state.value.results.single()
        assertFalse(result.targetCompleted)
        assertEquals(TypingTestInputKind.UNKNOWN, result.inputKind)
        assertEquals(TypingTestFraction(1, 1), result.top1HitRate)
        assertTrue(result.calibrationSamples.isEmpty())
    }

    @Test fun completionWithoutObservedNativeCommitCannotInventKnownTruth() {
        val info = start()
        type(info)
        TypingTestSession.completePhrase(prompt.text)
        val result = TypingTestSession.state.value.results.single()
        assertEquals(TypingTestInputKind.UNKNOWN, result.inputKind)
        assertEquals(TypingTestFraction(1, 1), result.top1HitRate)
        assertTrue(result.calibrationSamples.isEmpty())
    }

    @Test fun promptedTouchesRemainOriginalEvidenceAndReportsOmitEditorMetadata() {
        val info = start()
        val cells = ('a'..'z').mapIndexed { i, key -> KeyCell(key, i * 40f, 0f,
            (i + 1) * 40f, 50f) }
        var raw = ""
        prompt.pinyin.forEach { letter ->
            val cell = cells.first { it.letter == letter }
            val evidence = PinyinTapEvidence(TapEvidence(letter, cell.centerX, cell.centerY, 1f), cells)
            val ticket = TypingTestSession.beginKey(info, letter, evidence,
                orientation = Configuration.ORIENTATION_PORTRAIT)!!
            raw += letter
            finish(ticket, raw)
        }
        val result = commitAndComplete(info)
        assertEquals(prompt.pinyin.length, result.calibrationSamples.size)
        assertTrue(result.calibrationSamples.all { it.originalKey == it.intendedKey &&
            it.orientation == "portrait" && it.hand == "unknown" })
        val report = TypingTestSession.exportReport()!!
        assertFalse(report.contains(info.hintText.toString()))
        assertFalse(report.contains("fieldId"))
        assertFalse(report.contains("packageName"))
        assertFalse(JSONObject(report).getBoolean("automatic_touch_training"))
        assertTrue(AppPrefs.getInstance().keyboard.pinyinTouchPersonalization.getValue())
        assertEquals(prompt.pinyin.length, TypingTestSession.confirmedCalibrationSamples().size)
        assertTrue(TypingTestSession.confirmedCalibrationSamples().all {
            it.confirmationId?.matches(Regex("[a-f0-9]{64}")) == true
        })
    }

    @Test fun actualKeyboardSuppliesControlGroupEvidenceOnlyWhileTestEditorIsEligible() {
        val prefs = AppPrefs.getInstance().keyboard
        prefs.pinyinTouchCorrection.setValue(false)
        prefs.pinyinTouchAlternatives.setValue(false)
        prefs.pinyinDownOrder.setValue(false)
        prefs.popupOnKeyPress.setValue(false)
        val themePrefs = ThemeManager.prefs
        val oldMotion = themePrefs.keyMotionEffect.getValue()
        val oldEffect = themePrefs.pressEffect.getValue()
        val oldBreathing = themePrefs.idleBreathing.getValue()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var attachedKeyboard: TextKeyboard? = null
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        try {
            themePrefs.keyMotionEffect.setValue(ThemePrefs.KeyMotionEffect.Off)
            themePrefs.pressEffect.setValue(false)
            themePrefs.idleBreathing.setValue(false)
            val info = start()
            activity.setTheme(R.style.Theme_InputViewTheme)
            val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09, useEffectTheme = false)
            attachedKeyboard = keyboard
            keyboard.downOrderEditorInfoProvider = { info }
            ReflectionHelpers.setField(keyboard, "chineseMode", true)
            val size = context.resources.displayMetrics.density
            val width = (360 * size).toInt()
            val height = (260 * size).toInt()
            activity.setContentView(FrameLayout(activity).apply {
                addView(keyboard, FrameLayout.LayoutParams(width, height))
            })
            controller.visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, width, height)
            fun keys(view: View): List<KeyView> = when (view) {
                is KeyView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
                else -> emptyList()
            }
            val key = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == "N" }
            var x = key.width / 2f
            var y = key.height / 2f
            var child: View = key
            while (child !== keyboard) {
                x += child.left
                y += child.top
                child = child.parent as View
            }
            val actions = mutableListOf<KeyAction.FcitxKeyAction>()
            keyboard.keyActionListener = KeyActionListener { action, _ ->
                if (action is KeyAction.FcitxKeyAction) actions += action
            }
            fun tap() {
                listOf(MotionEvent.ACTION_DOWN to 100L, MotionEvent.ACTION_UP to 110L).forEach { (action, time) ->
                    val event = MotionEvent.obtain(100L, time, action, x, y, 0)
                    try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
            }
            tap()
            assertEquals("n", actions.single().act)
            assertNotNull(actions.single().pinyinTapEvidence)
            assertEquals(26, actions.single().pinyinTapEvidence!!.cells.size)
            TypingTestSession.revokeEditor()
            actions.clear()
            tap()
            assertEquals("n", actions.single().act)
            assertNull(actions.single().pinyinTapEvidence)
        } finally {
            attachedKeyboard?.onDetach()
            controller.pause().stop().destroy()
            ShadowChoreographer.setPaused(false)
            themePrefs.keyMotionEffect.setValue(oldMotion)
            themePrefs.pressEffect.setValue(oldEffect)
            themePrefs.idleBreathing.setValue(oldBreathing)
        }
    }

    @Test fun nativeCommitFreezesSnapshotBeforeLaterKeyAndMainThreadDelivery() {
        val info = start()
        type(info, "nuhaoa", listOf("怒号啊"))
        val event = FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(prompt.text, -1))
        TypingTestSession.prepareNativeCommit(event)
        // Another serialized key finishes before the main-thread event collector catches up.
        val later = TypingTestSession.beginKey(info, 'n', null)!!
        finish(later, prompt.pinyin, listOf(prompt.text))
        TypingTestSession.observeCommit(info, event)
        TypingTestSession.completePhrase(prompt.text)
        val result = TypingTestSession.state.value.results.single()
        assertTrue(result.targetCompleted)
        assertEquals("nuhaoa", result.finalInputPinyin)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 1), result.top3HitRate)
    }

    @Test fun nativeCommitRequiresReadyGenerationAndExactEventReceiptOnce() {
        val info = start()
        val premature = FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(prompt.text, -1))
        TypingTestSession.prepareNativeCommit(premature)
        type(info)
        TypingTestSession.observeCommit(info, premature)
        val event = FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(prompt.text, -1))
        TypingTestSession.prepareNativeCommit(event)
        TypingTestSession.observeCommit(info, event.copy())
        TypingTestSession.observeCommit(info, event)
        TypingTestSession.observeCommit(info, event)
        TypingTestSession.completePhrase(prompt.text)
        val result = TypingTestSession.state.value.results.single()
        assertEquals(TypingTestInputKind.FULL_PINYIN, result.inputKind)
        assertEquals(prompt.pinyin, result.finalInputPinyin)
        assertEquals(TypingTestFraction(1, 1), result.top1HitRate)
    }

    @Test fun oldNativeEventCannotBecomeCommitInNewTrialOrReplacementEditor() {
        val previousEditor = start()
        type(previousEditor, "nuhaoa", listOf("怒号啊"))
        val stale = FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("old secret text", -1))
        TypingTestSession.prepareNativeCommit(stale)
        val replacement = start(editor())
        type(replacement)
        TypingTestSession.observeCommit(replacement, stale)
        TypingTestSession.observeCommit(previousEditor, stale)
        val current = FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(prompt.text, -1))
        TypingTestSession.prepareNativeCommit(current)
        TypingTestSession.observeCommit(replacement, current)
        TypingTestSession.completePhrase(prompt.text)
        val result = TypingTestSession.state.value.results.single()
        assertEquals(TypingTestInputKind.FULL_PINYIN, result.inputKind)
        assertTrue(result.targetCompleted)
        assertEquals(prompt.pinyin.length, result.letterKeyCount)
        assertFalse(TypingTestSession.exportReport()!!.contains("old secret text"))
    }

    @Test fun viewRecreationDiscardsCurrentTwoLettersAndPreservesCompletedResults() {
        val info = start()
        type(info)
        val completed = commitAndComplete(info)
        TypingTestSession.nextPhrase()
        TypingTestSession.setActive(true)
        val next = TypingTestSession.state.value.prompt!!
        type(info, next.pinyin.take(2), listOf(next.text))
        TypingTestSession.onViewDestroyed()
        assertFalse(TypingTestSession.isEligibleEditor(info))
        assertEquals(listOf(completed), TypingTestSession.state.value.results)
        assertEquals(next, TypingTestSession.state.value.prompt)
        assertNotNull(TypingTestSession.state.value.failure)
        TypingTestSession.setActive(true)
        type(info, next.pinyin, listOf(next.text))
        val repaired = commitAndComplete(info, next.text)
        assertEquals(next.pinyin, repaired.firstAttemptPinyin)
        assertEquals(next.pinyin.length, repaired.letterKeyCount)
        assertEquals(TypingTestInputKind.FULL_PINYIN, repaired.inputKind)
        assertEquals(listOf(completed, repaired), TypingTestSession.state.value.results)
        val report = JSONObject(TypingTestSession.exportReport()!!)
        assertEquals(1, report.getInt("discarded_on_view_recreation"))
    }

    @Test fun awaitedClearPreventsQueuedReportWriteOrReloadFromResurrectingReport(): Unit = runBlocking {
        val info = start()
        type(info)
        commitAndComplete(info)
        assertNotNull(TypingTestSession.exportReport())
        TypingTestSession.clearReports()
        assertNull(TypingTestSession.exportReport())
        assertFalse(TypingTestSession.state.value.reportAvailable)
        assertNull(TypingTestReportStore.readLatest(context))
        TypingTestSession.loadHistory(context)
        assertNull(TypingTestSession.exportReport())
        assertFalse(TypingTestSession.state.value.reportAvailable)
    }

    @Test fun loadingHistoryCannotReplaceActiveInMemoryTrial(): Unit = runBlocking {
        val info = start()
        TypingTestReportStore.write(context, 101L, "{\"old_report\":true}")
        TypingTestSession.loadHistory(context)
        assertEquals(TypingTestPhase.Typing, TypingTestSession.state.value.phase)
        assertEquals(prompt, TypingTestSession.state.value.prompt)
        assertNull(TypingTestSession.exportReport())
        assertTrue(TypingTestSession.isEligibleEditor(info))
    }

    @Test fun optionalStageAndOfferObservationRequiresOwnForegroundEditor() {
        val info = editor()
        assertNull(TypingTestSession.beginObservation(info))
        start(info)
        assertNotNull(TypingTestSession.beginObservation(info))
        assertNull(TypingTestSession.beginObservation(editor()))
        TypingTestSession.setActive(false)
        assertNull(TypingTestSession.beginObservation(info))
        start(info.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD })
        assertNull(TypingTestSession.beginObservation(info))
    }

    @Test fun delayedStageAndOfferResultsCannotEnterReactivatedOrReplacementEditor() {
        val info = start()
        val stale = TypingTestSession.beginObservation(info)!!
        TypingTestSession.setActive(false)
        TypingTestSession.setActive(true)
        TypingTestSession.recordStage(stale, TypingTestStage.TouchSearch, 1_000L)
        TypingTestSession.recordAlternative(stale, 1L, TypingTestAlternativeEventKind.Generated)
        val replaced = TypingTestSession.beginObservation(info)!!
        val next = editor()
        TypingTestSession.attachEditor(next)
        TypingTestSession.recordStage(replaced, TypingTestStage.SendKey, 2_000L)
        TypingTestSession.recordAlternative(replaced, 2L, TypingTestAlternativeEventKind.Published,
            originalPinyin = prompt.pinyin, candidateText = prompt.text)
        type(next)
        val result = commitAndComplete(next)
        assertEquals(0, result.stageLatencies.touchSearch.sampleCount)
        assertEquals(0, result.stageLatencies.sendKey.sampleCount)
        assertEquals(0, result.alternativeMetrics.generatedCount)
        assertEquals(0, result.alternativeMetrics.publishedCount)
    }

    @Test fun stageDurationsRemainSeparateFromOriginalKeyAndSnapshotSamples() {
        val info = start()
        val observation = TypingTestSession.beginObservation(info)!!
        TypingTestSession.recordStage(observation, TypingTestStage.SendKey, 8_000_000L)
        TypingTestSession.recordStage(observation, TypingTestStage.TouchSearch, 2_000_000L)
        TypingTestSession.recordStage(observation, TypingTestStage.AlternativeQuery, 3_000_000L)
        TypingTestSession.recordStage(observation, TypingTestStage.AlternativeQuery, -1L)
        type(info)
        val result = commitAndComplete(info)
        assertEquals(8_000_000L, result.stageLatencies.sendKey.p95Nanos)
        assertEquals(2_000_000L, result.stageLatencies.touchSearch.p95Nanos)
        assertEquals(3_000_000L, result.stageLatencies.alternativeQuery.p95Nanos)
        assertEquals(1, result.stageLatencies.alternativeQuery.invalidSampleCount)
        assertEquals(prompt.pinyin.length, result.latency.sampleCount)
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertEquals(1, row.getJSONArray("send_key_ns").length())
        assertEquals(prompt.pinyin.length, row.getJSONArray("test_snapshot_ns").length())
        assertEquals("send_key_ns", row.getJSONObject("stage_latencies")
            .getJSONObject("send_key").getString("measurement"))
    }

    @Test fun fullPhraseStarOfferHasItsOwnTruthAndCannotInflateInitialRimeSnapshot() {
        val info = start()
        type(info, candidates = listOf("怒号啊", "你好呀"))
        val observation = TypingTestSession.beginObservation(info)!!
        TypingTestSession.recordAlternative(observation, 9L, TypingTestAlternativeEventKind.Generated,
            originalPinyin = prompt.pinyin, alternativePinyin = prompt.pinyin, searchPathCount = 3)
        TypingTestSession.recordAlternative(observation, 9L, TypingTestAlternativeEventKind.Published,
            candidateText = prompt.text, originalRank = 4)
        // A repeated layout notification must not multiply display or hit denominators.
        repeat(2) { TypingTestSession.recordAlternative(observation, 9L,
            TypingTestAlternativeEventKind.Displayed) }
        TypingTestSession.recordAlternative(observation, 9L, TypingTestAlternativeEventKind.Selected)
        TypingTestSession.observeCommit(info, prompt.text)
        TypingTestSession.recordAlternative(observation, 9L, TypingTestAlternativeEventKind.Resolved,
            success = true)
        TypingTestSession.completePhrase(prompt.text)
        val result = TypingTestSession.state.value.results.last()
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 1), result.top3HitRate)
        assertEquals(TypingTestFraction(1, 1), result.alternativeMetrics.publishedHitRate)
        assertEquals(TypingTestFraction(1, 1), result.alternativeMetrics.targetSelectionRate)
        assertEquals(TypingTestFraction(1, 1), result.alternativeMetrics.selectionSuccessRate)
        assertEquals(1, result.alternativeMetrics.displayedCount)
        val report = JSONObject(TypingTestSession.exportReport()!!)
        assertEquals("axiang-typing-test-v2", report.getString("format"))
        val events = report.getJSONArray("trials").getJSONObject(0).getJSONArray("alternative_events")
        assertEquals(5, events.length())
        assertEquals(4, events.getJSONObject(4).getInt("original_rank_zero_based"))
        assertEquals(3, events.getJSONObject(4).getInt("search_path_count"))
        assertFalse(report.getBoolean("automatic_touch_training"))
    }

    @Test fun partialOfferAndUnpublishedClicksDoNotAcquireFullPhraseTruth() {
        val info = start()
        val observation = TypingTestSession.beginObservation(info)!!
        TypingTestSession.recordAlternative(observation, 10L, TypingTestAlternativeEventKind.Published,
            originalPinyin = "ni", candidateText = prompt.text)
        TypingTestSession.recordAlternative(observation, 11L, TypingTestAlternativeEventKind.Displayed)
        TypingTestSession.recordAlternative(observation, 11L, TypingTestAlternativeEventKind.Selected)
        TypingTestSession.recordAlternative(observation, 11L, TypingTestAlternativeEventKind.Resolved,
            success = true)
        type(info)
        val result = commitAndComplete(info)
        assertEquals(1, result.alternativeMetrics.publishedCount)
        assertEquals(TypingTestFraction(0, 0), result.alternativeMetrics.publishedHitRate)
        assertEquals(0, result.alternativeMetrics.displayedCount)
        assertEquals(0, result.alternativeMetrics.selectedCount)
        assertEquals(0, result.alternativeMetrics.resolvedCount)
    }

    @Test fun fullPhraseStarOfferAfterEarlyRepairIsSeparateFromUnscoredFirstAttempt() {
        val info = start()
        type(info, "n")
        val backspace = TypingTestSession.beginKey(info, null, null, backspace = true)!!
        finish(backspace, "", emptyList())
        type(info)
        val observation = TypingTestSession.beginObservation(info)!!
        TypingTestSession.recordAlternative(observation, 12L, TypingTestAlternativeEventKind.Published,
            originalPinyin = prompt.pinyin, candidateText = prompt.text)
        val result = commitAndComplete(info)
        assertEquals(TypingTestFraction(0, 0), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 0), result.top3HitRate)
        assertEquals(TypingTestFraction(1, 1), result.alternativeMetrics.publishedHitRate)
    }

    @Test fun boundedStageAndEventStorageExportsExplicitOmissionCounts() {
        val info = start()
        val observation = TypingTestSession.beginObservation(info)!!
        repeat(300) { index ->
            TypingTestSession.recordStage(observation, TypingTestStage.SendKey, index.toLong())
            TypingTestSession.recordAlternative(observation, index.toLong(),
                TypingTestAlternativeEventKind.Generated)
        }
        type(info)
        val result = commitAndComplete(info)
        assertEquals(256, result.stageLatencies.sendKey.sampleCount)
        assertEquals(256, result.alternativeMetrics.generatedCount)
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertEquals(44, row.getJSONObject("stage_omitted_counts").getInt("SendKey"))
        assertEquals(44, row.getInt("alternative_event_omitted_count"))
    }

    @Test fun completedTrialRejectsLateProbeEvenWithSameEditorObject() {
        val info = start()
        type(info)
        val stale = TypingTestSession.beginObservation(info)!!
        commitAndComplete(info)
        TypingTestSession.nextPhrase()
        TypingTestSession.setActive(true)
        TypingTestSession.recordStage(stale, TypingTestStage.AlternativeQuery, 11L)
        TypingTestSession.recordAlternative(stale, 15L, TypingTestAlternativeEventKind.Published,
            originalPinyin = prompt.pinyin, candidateText = prompt.text)
        val second = TypingTestPrompts.all[1]
        type(info, second.pinyin, listOf(second.text))
        val result = commitAndComplete(info, second.text)
        assertEquals(0, result.stageLatencies.alternativeQuery.sampleCount)
        assertEquals(0, result.alternativeMetrics.publishedCount)
    }

    @Test fun offerReadyMeasuresOriginalEnqueueToObservedPublicationAcrossCallbacksOnce() {
        val info = start()
        val enqueued = TypingTestSession.beginObservation(info)!!
        type(info)
        val beforePublish = System.nanoTime()
        TypingTestSession.recordAlternative(enqueued, 16L, TypingTestAlternativeEventKind.Published,
            originalPinyin = prompt.pinyin, candidateText = prompt.text)
        val afterPublish = System.nanoTime()
        val later = TypingTestSession.beginObservation(info)!!
        // A current receipt from a different callback cannot change this offer's start time.
        TypingTestSession.recordOfferReady(later, 16L)
        repeat(2) { TypingTestSession.recordOfferReady(enqueued, 16L) }
        val result = commitAndComplete(info)
        val ready = result.stageLatencies.offerReady
        assertEquals(1, ready.sampleCount)
        val observed = ready.p95Nanos!!
        assertTrue(observed >= beforePublish - enqueued.enqueueNanos)
        assertTrue(observed <= afterPublish - enqueued.enqueueNanos)
        assertEquals("enqueue_to_offer_publish_ns", ready.measurement)
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertEquals(1, row.getJSONArray("enqueue_to_offer_publish_ns").length())
        assertEquals(observed, row.getJSONObject("stage_latencies")
            .getJSONObject("offer_ready").getLong("p95_ns"))
    }

    @Test fun unpublishedOrOldGenerationOfferCannotFabricateReadyDuration() {
        val info = start()
        val old = TypingTestSession.beginObservation(info)!!
        TypingTestSession.recordOfferReady(old, 17L)
        TypingTestSession.recordStage(old, TypingTestStage.OfferReady, 12L)
        TypingTestSession.recordAlternative(old, 17L, TypingTestAlternativeEventKind.Generated,
            originalPinyin = prompt.pinyin)
        TypingTestSession.recordOfferReady(old, 17L)
        TypingTestSession.recordAlternative(old, 18L, TypingTestAlternativeEventKind.Published,
            originalPinyin = prompt.pinyin, candidateText = prompt.text)
        TypingTestSession.setActive(false)
        TypingTestSession.setActive(true)
        TypingTestSession.recordOfferReady(old, 18L)
        val fresh = TypingTestSession.beginObservation(info)!!
        TypingTestSession.recordOfferReady(fresh, 18L)
        type(info)
        val result = commitAndComplete(info)
        assertEquals(0, result.stageLatencies.offerReady.sampleCount)
        assertNull(result.stageLatencies.offerReady.p95Nanos)
    }


    @Test fun laterUnsupportedActionAndRepairCannotEraseInitialWrongFullPhrase() {
        val info = start()
        type(info, "nuhaoa", listOf("怒号啊", prompt.text))
        TypingTestSession.markUnsupported(info, "non_typing_action")
        val erase = TypingTestSession.beginKey(info, null, null, backspace = true)!!
        finish(erase, "nuhao")
        type(info, "a", prefix = "nuhao")
        val result = commitAndComplete(info)
        assertEquals(TypingTestInputKind.UNKNOWN, result.inputKind)
        assertEquals(TypingTestFraction(1, prompt.pinyin.length), result.rawEditRate)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(1, 1), result.top3HitRate)
        val report = JSONObject(TypingTestSession.exportReport()!!)
        assertEquals("FULL_PINYIN", report.getJSONArray("trials").getJSONObject(0)
            .getString("first_attempt_input_kind"))
        assertTrue(report.getString("summary").contains("原 Rime 快照覆盖：1/1"))
    }

    @Test fun visibleSnapshotRequiresExactEditorSameFirstCompositionAndFreezesFirstLayout() {
        val info = start()
        type(info, candidates = listOf("你好呀", prompt.text))
        TypingTestSession.recordDisplayedCandidates(editor(), prompt.pinyin, listOf("foreign"))
        TypingTestSession.recordDisplayedCandidates(info, "nuhaoa", listOf("stale"))
        TypingTestSession.recordDisplayedCandidates(info, prompt.pinyin, listOf(prompt.text, "你好呀"))
        TypingTestSession.recordDisplayedCandidates(info, prompt.pinyin, listOf("newer"))
        val result = commitAndComplete(info)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(1, 1), result.displayedTop1HitRate)
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertEquals(prompt.text, row.getJSONObject("displayed_candidate_snapshot")
            .getJSONArray("candidates").getString(0))
    }

    @Test fun queryUnavailabilityAndTimeoutAreVisibleAndDistinctFromEmptyResults() {
        val info = start()
        val observation = TypingTestSession.beginObservation(info)!!
        TypingTestSession.recordAlternative(observation, 30, TypingTestAlternativeEventKind.Rejected,
            reason = "ProbeUnavailable:VersionMismatch:1.12.0")
        TypingTestSession.recordAlternative(observation, 31, TypingTestAlternativeEventKind.Rejected,
            reason = "ProbeOverBudget")
        type(info)
        commitAndComplete(info)
        assertTrue(TypingTestSession.state.value.reportWarning!!.contains("不可用 1 次"))
        assertTrue(TypingTestSession.state.value.reportWarning!!.contains("1 次查询超时"))
        assertTrue(JSONObject(TypingTestSession.exportReport()!!).getString("query_warning").contains("不可用"))
        val next = start()
        val empty = TypingTestSession.beginObservation(next)!!
        TypingTestSession.recordAlternative(empty, 32, TypingTestAlternativeEventKind.Rejected,
            reason = "ProbeNoCandidates")
        type(next)
        commitAndComplete(next)
        assertNull(TypingTestSession.state.value.reportWarning)
    }


    @Test fun earlyConfirmationRecordsDispatchAndLaterPhysicalUpSeparately() {
        val info = start()
        val cell = KeyCell('n', 0f, 0f, 40f, 50f)
        val evidence = PinyinTapEvidence(TapEvidence('n', 20f, 25f, 1f),
            listOf(cell), pointerId = 1, downTime = 100L, contactId = 901L,
            downSequence = 1L, dispatchSequence = 2L, dispatchTime = 140L)
        val ticket = TypingTestSession.beginKey(info, 'n', evidence,
            orientation = Configuration.ORIENTATION_PORTRAIT)!!
        finish(ticket, "n")
        TypingTestSession.completeTouchEvidence(901L, 160L, 21f, 26f)
        TypingTestSession.completeTouchEvidence(901L, 200L, 22f, 27f)
        commitAndComplete(info)
        val touch = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials")
            .getJSONObject(0).getJSONArray("first_attempt_touches").getJSONObject(0)
        assertEquals(100L, touch.getLong("down_time_ms"))
        assertEquals(140L, touch.getLong("dispatch_time_ms"))
        assertEquals(160L, touch.getLong("physical_up_time_ms"))
        assertEquals(21.0, touch.getDouble("physical_up_x"), 0.0)
        assertEquals(1, touch.getInt("pointer_id"))
        assertEquals(2L, touch.getLong("dispatch_sequence"))
    }

    @Test fun stalePhysicalUpCannotCompleteTouchAfterEditorReactivation() {
        val info = start()
        val cell = KeyCell('n', 0f, 0f, 40f, 50f)
        val evidence = PinyinTapEvidence(TapEvidence('n', 20f, 25f, 1f),
            listOf(cell), pointerId = 1, downTime = 100L, contactId = 902L)
        val ticket = TypingTestSession.beginKey(info, 'n', evidence)!!
        finish(ticket, "n")
        TypingTestSession.setActive(false)
        TypingTestSession.setActive(true)
        TypingTestSession.completeTouchEvidence(902L, 160L, 21f, 26f)
        commitAndComplete(info)
        val touch = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials")
            .getJSONObject(0).getJSONArray("first_attempt_touches").getJSONObject(0)
        assertTrue(touch.isNull("physical_up_time_ms"))
    }


    @Test fun keyTimelineIncludesBackspaceAndQueueReceiptForRepairAssociation() {
        val info = start()
        type(info, "nu")
        val erase = TypingTestSession.beginKey(info, null, null, backspace = true)!!
        finish(erase, "n")
        type(info, "ihaoa", prefix = "n")
        commitAndComplete(info)
        val timeline = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials")
            .getJSONObject(0).getJSONArray("key_timeline")
        assertEquals(8, timeline.length())
        val deletion = timeline.getJSONObject(2)
        assertEquals(3, deletion.getInt("key_ordinal"))
        assertTrue(deletion.getBoolean("backspace"))
        assertTrue(deletion.isNull("letter"))
        assertTrue(deletion.getLong("finished_monotonic_ns") >= deletion.getLong("started_monotonic_ns"))
        assertEquals(deletion.getLong("started_monotonic_ns") - deletion.getLong("enqueue_monotonic_ns"),
            deletion.getLong("queue_wait_ns"))
    }


    @Test fun anExtraTailLetterIsRetainedUntilFirstRepairWithoutChangingNativeSnapshotWindow() {
        val info = start()
        type(info, prompt.pinyin + "a")
        val erase = TypingTestSession.beginKey(info, null, null, backspace = true)!!
        finish(erase, prompt.pinyin)
        val result = commitAndComplete(info)
        assertEquals(prompt.pinyin + "a", result.firstAttemptPinyin)
        assertEquals(TypingTestFraction(1, prompt.pinyin.length), result.rawEditRate)
        assertEquals(TypingTestFraction(1, 1), result.top1HitRate)
        assertTrue(result.calibrationSamples.isEmpty())
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertEquals(prompt.pinyin, row.getJSONObject("candidate_snapshot").getString("raw"))
    }

    @Test fun eachProbeCallKeepsItsOfferStageAndCacheIdentityAndRejectsStaleReceipts() {
        val info = start()
        val observation = TypingTestSession.beginObservation(info)!!
        val cold = RimeTouchProbe.QueryResult(emptyList(), 25_000_000, true, false,
            libraryLoadNanos = 2_000_000, initializationNanos = 22_000_000,
            nativeQueryNanos = 500_000, coldInitialization = true, nativeWithinBudget = true)
        TypingTestSession.recordProbeQuery(observation, 40, cold)
        TypingTestSession.recordProbeQuery(observation, 40, cold)
        TypingTestSession.recordProbeQuery(observation, 41, cold.copy(elapsedNanos = 20_000,
            libraryLoadNanos = 0, initializationNanos = 0, nativeQueryNanos = 0,
            cacheHit = true, coldInitialization = false, withinBudget = true))
        TypingTestSession.setActive(false)
        TypingTestSession.setActive(true)
        TypingTestSession.recordProbeQuery(observation, 42, cold)
        type(info)
        commitAndComplete(info)
        val queries = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials")
            .getJSONObject(0).getJSONArray("probe_queries")
        assertEquals(2, queries.length())
        assertTrue(queries.getJSONObject(0).getBoolean("cold_initialization"))
        assertFalse(queries.getJSONObject(0).getBoolean("within_budget"))
        assertTrue(queries.getJSONObject(0).getBoolean("native_within_budget"))
        assertTrue(queries.getJSONObject(1).getBoolean("cache_hit"))
        assertEquals(0L, queries.getJSONObject(1).getLong("native_query_ns"))
    }


    @Test fun unsupportedTailExcludesRawWindowButPreservesEarlierNativeCandidateTruth() {
        val info = start()
        type(info)
        val extra = TypingTestSession.beginKey(info, 'x', null, supported = false)!!
        finish(extra, prompt.pinyin + "x")
        val result = commitAndComplete(info)
        assertEquals(prompt.pinyin + "x", result.firstAttemptPinyin)
        assertEquals(TypingTestFraction(0, 0), result.rawEditRate)
        assertEquals(TypingTestFraction(1, 1), result.top1HitRate)
        assertTrue(result.calibrationSamples.isEmpty())
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertEquals("UNKNOWN", row.getString("raw_attempt_input_kind"))
        assertEquals("FULL_PINYIN", row.getString("first_attempt_input_kind"))
    }


    @Test fun identicalSpellingAfterRepairCannotPretendItsVisibleCandidatesBelongToFirstLayout() {
        val info = start()
        type(info, "nuhaoa", listOf("怒号啊"))
        val erase = TypingTestSession.beginKey(info, null, null, backspace = true)!!
        finish(erase, "nuhao")
        type(info, "a", prefix = "nuhao", candidates = listOf(prompt.text))
        TypingTestSession.recordDisplayedCandidates(info, "nuhaoa", listOf(prompt.text))
        val result = commitAndComplete(info)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 0), result.displayedTop1HitRate)
        assertEquals(TypingTestFraction(0, 0), result.displayedTop3HitRate)
    }

    private fun beginPrediction(info: EditorInfo, prefix: String = "你",
                                token: Long = 1L): TypingTestObservationTicket {
        val observation = TypingTestSession.beginObservation(info)!!
        // Production observes this native receipt only after editor commit success.
        TypingTestSession.observeCommit(info, prefix)
        TypingTestSession.recordPredictionCommit(observation, token, true, prefix, 100L)
        TypingTestSession.recordPredictionPublished(observation, token, token + 100L,
            TypingTestPredictionWarmth.Warm)
        return observation
    }

    @Test fun predictionDirectSuccessUpdatesLedgerExactlyOnceWithoutManualRawOrCalibrationTruth() {
        val info = start()
        type(info, "ni", listOf("你"))
        val observation = beginPrediction(info)
        TypingTestSession.recordPredictionDrawn(observation, 101L, listOf(0), 140L)
        TypingTestSession.recordPredictionSelected(observation, 101L, 0)
        TypingTestSession.recordPredictionResolved(observation, 101L, 0, "好啊", true)
        // The controller reports the successful newly inserted word afterwards to chain predictions.
        val clickObservation = TypingTestSession.beginObservation(info)!!
        TypingTestSession.recordPredictionCommit(clickObservation, 2L, true, "好啊", 160L)
        TypingTestSession.recordPredictionResolved(observation, 101L, 0, "好啊", true)
        TypingTestSession.completePhrase(prompt.text)
        val result = TypingTestSession.state.value.results.single()
        assertTrue(result.targetCompleted)
        assertEquals(1, result.predictionMetrics.adoptedCount)
        assertEquals(4, result.predictionMetrics.estimatedPinyinLetterKeys)
        assertEquals(40L, result.predictionMetrics.drawLatency.p95Nanos)
        assertNull(result.top1HitRate.value)
        assertNull(result.rawEditRate.value)
        assertTrue(result.calibrationSamples.isEmpty())
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertFalse(row.getJSONArray("session_exclusions").toString().contains("unobserved_or_edited_commit"))
        assertEquals(prompt.text, row.getString("committed_text"))
        assertEquals(2, row.getJSONArray("prediction_commits").length())
        val events = row.getJSONArray("prediction_events")
        for (index in 0 until events.length()) {
            assertFalse(events.getJSONObject(index).has("candidate_text"))
            assertFalse(events.getJSONObject(index).has("context"))
        }
        assertFalse(row.getBoolean("calibration_confirmed"))
        assertTrue(TypingTestSession.state.value.reportSummary!!.contains("4"))
    }

    @Test fun predictionCheckedFailureDoesNotAppendTextOrAdopt() {
        val info = start()
        val observation = beginPrediction(info)
        TypingTestSession.recordPredictionDrawn(observation, 101L, listOf(0), 140L)
        TypingTestSession.recordPredictionSelected(observation, 101L, 0)
        TypingTestSession.recordPredictionResolved(observation, 101L, 0, "好啊", false)
        TypingTestSession.completePhrase("你")
        val result = TypingTestSession.state.value.results.single()
        assertEquals(0, result.predictionMetrics.adoptedCount)
        assertNull(result.predictionMetrics.targetHitRate.value)
        val row = JSONObject(TypingTestSession.exportReport()!!).getJSONArray("trials").getJSONObject(0)
        assertEquals("你", row.getString("committed_text"))
        assertFalse(row.getJSONArray("session_exclusions").toString().contains("prediction_input_used"))
    }

    @Test fun stalePredictionReceiptCannotCaptureAnotherTrialOrEditor() {
        val info = start()
        val observation = beginPrediction(info)
        val replacement = editor()
        TypingTestSession.attachEditor(replacement)
        TypingTestSession.recordPredictionDrawn(observation, 101L, listOf(0), 140L)
        TypingTestSession.recordPredictionSelected(observation, 101L, 0)
        TypingTestSession.recordPredictionResolved(observation, 101L, 0, "好啊", true)
        TypingTestSession.recordPredictionQuery(observation, 1L, "Unavailable",
            TypingTestPredictionWarmth.Cold, false, 10L, 9L, 1L)
        TypingTestSession.completePhrase("你")
        val result = TypingTestSession.state.value.results.single().predictionMetrics
        assertEquals(0, result.drawnCount)
        assertEquals(0, result.selectedCount)
        assertEquals(0, result.adoptedCount)
        assertTrue(result.queryOutcomes.isEmpty())
    }

    @Test fun predictionPasswordAndOrdinaryFieldsReceiveNoTestReceiptsOrRecords() {
        for (info in listOf(editor().apply { inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD }, editor().apply { fieldId = FIELD_ID + 1 })) {
            start(info)
            val observation = TypingTestSession.beginObservation(info)
            assertNull(observation)
            TypingTestSession.recordPredictionCommit(observation, 1L, true, "private context", 100L)
            TypingTestSession.recordPredictionPublished(observation, 1L, 101L, TypingTestPredictionWarmth.Warm)
            TypingTestSession.recordPredictionDrawn(observation, 101L, listOf(0), 140L)
            TypingTestSession.recordPredictionSelected(observation, 101L, 0)
            TypingTestSession.recordPredictionResolved(observation, 101L, 0, "private suggestion", true)
            TypingTestSession.completePhrase("")
            val result = TypingTestSession.state.value.results.single().predictionMetrics
            assertEquals(0, result.successfulCommitCount)
            assertEquals(0, result.publishedCount)
            assertEquals(0, result.adoptedCount)
            assertFalse(TypingTestSession.exportReport()!!.contains("private context"))
            assertFalse(TypingTestSession.exportReport()!!.contains("private suggestion"))
        }
    }

    @Test fun explicitCursorEditMakesEstimateUnknownWhilePreservingActualPredictionUsage() {
        val info = start()
        val observation = beginPrediction(info)
        TypingTestSession.markUnsupported(info, "prediction_context_edited")
        TypingTestSession.recordPredictionDrawn(observation, 101L, listOf(0), 140L)
        TypingTestSession.recordPredictionSelected(observation, 101L, 0)
        TypingTestSession.recordPredictionResolved(observation, 101L, 0, "好啊", true)
        TypingTestSession.completePhrase(prompt.text)
        val result = TypingTestSession.state.value.results.single().predictionMetrics
        assertEquals(1, result.adoptedCount)
        assertEquals(1, result.drawLatency.sampleCount)
        assertNull(result.targetHitRate.value)
        assertEquals(TypingTestFraction(0, 1), result.savingsCoverage)
        assertEquals(1, result.unscoredReasons["append_scope_not_reliable"])
    }

    @Test fun nativeCandidateSelectionCanStillSupplyReliablePredictionTargetAlignment() {
        val info = start()
        TypingTestSession.markUnsupported(info, "non_typing_action")
        val observation = beginPrediction(info)
        TypingTestSession.recordPredictionSelected(observation, 101L, 0)
        TypingTestSession.recordPredictionResolved(observation, 101L, 0, "好啊", true)
        TypingTestSession.completePhrase(prompt.text)
        assertEquals(4, TypingTestSession.state.value.results.single().predictionMetrics.estimatedPinyinLetterKeys)
    }

    @Test fun predictionUnavailableAndTimeoutAreVisibleDiagnosticsWithUnknownAccuracy() {
        val info = start()
        val observation = beginPrediction(info)
        TypingTestSession.recordPredictionQuery(observation, 1L, "Unavailable",
            TypingTestPredictionWarmth.Cold, false, 10L, 9L, 1L)
        TypingTestSession.completePhrase("你")
        val state = TypingTestSession.state.value
        assertNotNull(state.reportWarning)
        assertEquals(1, state.results.single().predictionMetrics.queryOutcomes["Unavailable"])
        assertNull(state.results.single().predictionMetrics.targetHitRate.value)
        val report = JSONObject(TypingTestSession.exportReport()!!)
        assertEquals(1, report.getJSONObject("prediction_summary").getJSONObject("query_outcomes")
            .getInt("Unavailable"))
        assertTrue(report.getString("prediction_draw_measurement").contains("not_hardware_frame"))
    }

    companion object { private val FIELD_ID = R.id.typing_test_input }
}
