/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.otp.VerificationCodes
import org.fcitx.fcitx5.android.data.otp.SmsCodeStatus
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ClipboardSuggestionDismissals
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.prediction.NextWordPredictionOffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.lang.reflect.Proxy

/** Exercises the real toolbar and actual editor acceptance without starting the native daemon. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SmsCodeSuggestionUiTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private var previousApplication: FcitxApplication? = null
    private var previousPrefs: Any? = null
    private lateinit var stored: SharedPreferences

    @Before fun prepare() {
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = field.get(null) as? FcitxApplication
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        field.set(null, app)
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            previousPrefs = get(null)
            set(null, null)
        }
        stored = application.getSharedPreferences("sms-code-suggestion-ui", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        AppPrefs.init(stored)
        AppPrefs.getInstance().clipboard.verificationCodeFromSms.setValue(true)
        shadowOf(application).grantPermissions(Manifest.permission.RECEIVE_SMS)
        VerificationCodes.consume()
        SmsCodeStatus.reset()
        clearSmsDismissal()
    }

    @After fun restore() {
        VerificationCodes.consume()
        SmsCodeStatus.reset()
        clearSmsDismissal()
        shadowOf(application).denyPermissions(Manifest.permission.RECEIVE_SMS)
        val listener = AppPrefs::class.java.getDeclaredField("onSharedPreferenceChangeListener")
            .apply { isAccessible = true }.get(AppPrefs.getInstance()) as SharedPreferences.OnSharedPreferenceChangeListener
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

    private fun allViews(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) }
        else emptyList()

    private fun clearSmsDismissal() {
        ReflectionHelpers.getField<MutableMap<ClipboardSuggestionDismissals.Source,
            ClipboardSuggestionDismissals.Token>>(ClipboardSuggestionDismissals.shared, "dismissed")
            .remove(ClipboardSuggestionDismissals.Source.Sms)
    }

    private class Editor(context: Context) : BaseInputConnection(View(context), true) {
        val buffer: Editable get() = requireNotNull(editable)
        var acceptsCommit = true
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            acceptsCommit && super.commitText(text, newCursorPosition)
    }

    /** Substitute only the engine boundary; the complete InputView and broadcast pipeline run. */
    private class IdleEngine : FcitxConnection {
        val events = MutableSharedFlow<FcitxEvent<*>>(extraBufferCapacity = 16)
        private val ime = InputMethodEntry("keyboard-us")
        private val api = Proxy.newProxyInstance(FcitxAPI::class.java.classLoader,
            arrayOf(FcitxAPI::class.java)) { _, method, _ ->
            when (method.name) {
                "getInputMethodEntryCached" -> ime
                "getStatusAreaActionsCached" -> emptyArray<Action>()
                "getClientPreeditCached" -> FormattedText.Empty
                "getInputPanelCached" -> FcitxEvent.InputPanelEvent.Data()
                "getEventFlow" -> events
                else -> error("Unexpected native call in idle SMS UI test: ${method.name}")
            }
        } as FcitxAPI
        override val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = runBlocking { block(api) }
        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = block(api)
        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) { runBlocking { block(api) } }
    }

    private inner class Harness(fullInputView: Boolean = false) : AutoCloseable {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        private val scope = DynamicScope()
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val editor = Editor(application)
        val bar: KawaiiBarComponent
        val candidates: HorizontalCandidateComponent
        val inputView: InputView?
        private val engine: IdleEngine?
        private val theme: Theme = ThemePreset.XuancaiBlackV09
        private val uiContext: ContextThemeWrapper = ContextThemeWrapper(activity, R.style.Theme_InputViewTheme)

        init {
            service.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            service.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            ReflectionHelpers.setField(service, "mStartedInputConnection", editor)
            ReflectionHelpers.getField<CursorTracker>(service, "selection").resetTo(0)
            if (fullInputView) {
                // This test covers SMS UI, not the independently tested native touch probe.
                AppPrefs.getInstance().keyboard.apply {
                    pinyinTouchCorrection.setValue(false)
                    pinyinTouchAlternatives.setValue(false)
                    pinyinTouchPersonalization.setValue(false)
                }
                engine = IdleEngine()
                ReflectionHelpers.setField(service, "fcitx", engine)
                inputView = InputView(service, engine, theme)
                ReflectionHelpers.setField(service, "inputView", inputView)
                bar = ReflectionHelpers.getField(inputView, "kawaiiBar")
                candidates = ReflectionHelpers.getField(inputView, "horizontalCandidate")
                activity.setContentView(inputView)
                inputView.startInput(EditorInfo(), CapabilityFlags(0uL))
                inputView.handleEvents = true
            } else {
                inputView = null
                engine = null
                bar = KawaiiBarComponent()
                candidates = HorizontalCandidateComponent()
                scope += uiContext.wrapToUniqueComponent()
                scope += theme.wrapToUniqueComponent()
                scope += service.wrapToUniqueComponent()
                scope += CommonKeyActionListener()
                scope += PopupComponent()
                scope += candidates
                scope += bar
                bar.onScopeSetupFinished(scope)
                activity.setContentView(bar.view)
                bar.onStartInput(EditorInfo(), CapabilityFlags(0uL))
            }
            settle()
        }

        fun settle(milliseconds: Long = 16L) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
            repeat(2) {
                val root = inputView ?: bar.view
                val height = if (inputView == null) 52 else 800
                root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 360, height)
            }
        }

        fun predict(token: Long = 1L) {
            val offer = NextWordPredictionOffer(token, listOf("快乐", "朋友"))
            if (inputView != null) inputView.showNextWordPrediction(offer)
            else {
                candidates.setPredictionOffer(offer)
                bar.setNextWordPredictionVisible(true)
            }
            settle()
        }

        fun sms(code: String = "482913", age: Long = 0L) {
            VerificationCodes.publish(code, VerificationCodes.Source.Sms, System.currentTimeMillis() - age)
            settle()
        }

        fun startEditor(inputType: Int) {
            val info = EditorInfo().apply { this.inputType = inputType }
            if (inputView != null) inputView.startInput(info, CapabilityFlags.fromEditorInfo(info))
            else {
                bar.onStartInput(info, CapabilityFlags.fromEditorInfo(info))
                bar.onKeyboardLayoutSwitched(inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER)
            }
            settle()
        }

        fun nativeIdleEvents() {
            val flow = requireNotNull(engine).events
            assertTrue(flow.subscriptionCount.value > 0)
            assertTrue(flow.tryEmit(FcitxEvent.ClientPreeditEvent(FormattedText.Empty)))
            assertTrue(flow.tryEmit(FcitxEvent.InputPanelEvent(FcitxEvent.InputPanelEvent.Data())))
            assertTrue(flow.tryEmit(FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(total = 0))))
            settle()
        }

        fun numberRow(): NumberRow = allViews(bar.view).filterIsInstance<NumberRow>().single()

        /** Exercise the same installed gesture callback as a user expanding the password row. */
        fun manuallyShowNumberRow() {
            AppPrefs.getInstance().keyboard.toolbarNumRowOnPassword.setValue(true)
            startEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            assertTrue(numberRow().isShown)
            numberRow().onCollapseListener!!.invoke()
            settle()
            assertFalse(numberRow().isShown)
            val button = allViews(bar.view).filterIsInstance<ToolButton>().single {
                it.contentDescription == activity.getString(R.string.hide_keyboard)
            }
            assertTrue(requireNotNull(button.onGestureListener).onGesture(button,
                CustomGestureView.Event(CustomGestureView.GestureType.Up, false,
                    -80f, 26f, -1, 0, -106, 0)))
            settle()
            assertTrue(numberRow().isShown)
        }

        fun clipboard(text: String) {
            AppPrefs.getInstance().clipboard.clipboardSuggestion.setValue(true)
            ReflectionHelpers.getField<ClipboardManager.OnClipboardUpdateListener>(bar,
                "onClipboardUpdateListener").onUpdate(ClipboardEntry(id = 782, text = text))
            settle()
        }

        fun chip(): TextView = allViews(bar.view).filterIsInstance<TextView>().single {
            it.text.toString().startsWith("短信验证码")
        }

        fun assertSmsShown(code: String = "482913") {
            assertTrue("A live SMS chip must block the idle prediction surface", bar.isSmsCodeSuggestionActive())
            assertTrue("The code must be shown, not only cached", chip().isShown)
            assertTrue(chip().text.toString().contains(code))
            assertFalse("Next-word candidates must not cover the code", candidates.view.isShown)
        }

        fun dismiss() {
            allViews(bar.view).single {
                it.contentDescription == activity.getString(R.string.dismiss_clipboard_suggestion)
            }.performClick()
            settle()
        }

        fun tapCode() {
            ((chip().parent as View).parent as View).performClick()
            settle()
        }

        override fun close() {
            bar.dispose()
            service.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            service.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            controller.pause().stop().destroy()
            scope.clear()
            engine?.lifecycleScope?.cancel()
        }
    }

    @Test fun incomingSmsReplacesVisiblePredictionAndAlsoBlocksLatePrediction() {
        Harness().use { h ->
            h.predict()
            assertTrue(h.candidates.view.isShown)
            h.sms()
            h.assertSmsShown()
            h.predict(2)
            h.assertSmsShown()
        }
    }

    @Test fun incomingSmsCoversManualNumberRowAndDismissRestoresTheUsersChoice() {
        Harness().use { h ->
            h.manuallyShowNumberRow()
            h.sms()
            h.assertSmsShown()
            assertFalse(h.numberRow().isShown)
            h.dismiss()
            assertFalse(h.chip().isShown)
            assertTrue(h.numberRow().isShown)
        }
    }

    @Test fun smsExpiryRestoresManualNumberRowWithoutAnotherInputEvent() {
        Harness().use { h ->
            h.manuallyShowNumberRow()
            h.sms(age = VerificationCodes.TTL_MS - 1_000L)
            h.assertSmsShown()
            h.settle(1_100L)
            assertFalse(h.chip().isShown)
            assertTrue(h.numberRow().isShown)
        }
    }

    @Test fun ordinaryClipboardDoesNotCoverAManuallyOpenedNumberRow() {
        Harness().use { h ->
            h.manuallyShowNumberRow()
            h.clipboard("测试剪贴板内容")
            assertTrue(h.numberRow().isShown)
            assertFalse(allViews(h.bar.view).filterIsInstance<TextView>().single {
                it.text.toString() == "测试剪贴板内容"
            }.isShown)
        }
    }

    @Test fun incomingSmsCanBeTappedIntoADigitsEditor() {
        Harness().use { h ->
            h.startEditor(InputType.TYPE_CLASS_NUMBER)
            h.sms()
            h.assertSmsShown()
            h.tapCode()
            assertEquals("482913", h.editor.buffer.toString())
            assertNull(VerificationCodes.fresh())
        }
    }

    @Test fun incomingSmsCanBeTappedIntoANumericPasswordEditor() {
        Harness().use { h ->
            h.startEditor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            h.sms()
            h.assertSmsShown()
            h.tapCode()
            assertEquals("482913", h.editor.buffer.toString())
            assertNull(VerificationCodes.fresh())
        }
    }

    @Test fun incomingSmsCoversAutomaticPasswordNumberRowAndCanBeTappedIntoTheEditor() {
        AppPrefs.getInstance().keyboard.toolbarNumRowOnPassword.setValue(true)
        Harness().use { h ->
            h.startEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            assertTrue(h.numberRow().isShown)
            h.sms()
            h.assertSmsShown()
            assertFalse(h.numberRow().isShown)
            h.tapCode()
            assertEquals("482913", h.editor.buffer.toString())
            assertNull(VerificationCodes.fresh())
            assertTrue(h.numberRow().isShown)
        }
    }

    @Test fun smsReceivedBeforeToolbarConstructionIsReplayedAfterStartAndNativeIdleEvents() {
        VerificationCodes.publish("8361", VerificationCodes.Source.Sms)
        Harness().use { h ->
            h.assertSmsShown("8361")
            h.bar.onPreeditEmptyStateUpdate(true)
            h.bar.onCandidateUpdate(FcitxEvent.CandidateListEvent.Data(total = 0))
            h.settle()
            h.assertSmsShown("8361")
        }
    }

    @Test fun fullInputViewReplaysANewCodeInTheNumericLayoutAcrossNativeIdleAndLatePrediction() {
        VerificationCodes.publish("8361", VerificationCodes.Source.Sms)
        Harness(fullInputView = true).use { h ->
            h.startEditor(InputType.TYPE_CLASS_NUMBER)
            h.assertSmsShown("8361")
            assertFalse(requireNotNull(h.inputView).nextWordPredictionSurfaceVisible())
            h.nativeIdleEvents()
            h.assertSmsShown("8361")
            h.predict(7)
            h.assertSmsShown("8361")
            h.tapCode()
            assertEquals("8361", h.editor.buffer.toString())
            assertNull(VerificationCodes.fresh())
        }
    }

    @Test fun preparationStatusCountsOnlyFreshPermittedUndismissedSmsOffers() {
        shadowOf(application).denyPermissions(Manifest.permission.RECEIVE_SMS)
        Harness().use { h ->
            h.sms()
            assertEquals(0, SmsCodeStatus.snapshot().prepared)
            shadowOf(application).grantPermissions(Manifest.permission.RECEIVE_SMS)
            AppPrefs.getInstance().clipboard.verificationCodeFromSms.fireChange()
            h.settle()
            h.assertSmsShown()
            assertEquals(1, SmsCodeStatus.snapshot().prepared)
            h.dismiss()
            h.startEditor(InputType.TYPE_CLASS_NUMBER)
            assertEquals(1, SmsCodeStatus.snapshot().prepared)
            h.sms("8361", age = VerificationCodes.TTL_MS + 1_000L)
            assertEquals(1, SmsCodeStatus.snapshot().prepared)
        }
    }

    @Test fun nativeComposingCandidatesRetainPriorityThenTheCodeReturns() {
        Harness().use { h ->
            h.sms()
            h.candidates.adapter.updateCandidates(arrayOf(CandidateWord("", "正在", "")), 1)
            h.bar.onPreeditEmptyStateUpdate(false)
            h.bar.onCandidateUpdate(FcitxEvent.CandidateListEvent.Data(
                candidates = arrayOf(CandidateWord("", "正在", "")), total = 1))
            h.settle()
            assertTrue(h.candidates.view.isShown)
            assertFalse(h.chip().isShown)
            h.candidates.adapter.updateCandidates(emptyArray(), 0)
            h.bar.onPreeditEmptyStateUpdate(true)
            h.bar.onCandidateUpdate(FcitxEvent.CandidateListEvent.Data(total = 0))
            h.settle()
            h.assertSmsShown()
        }
    }

    @Test fun dismissRestoresPredictionAndOnlyANewSmsCanReopenTheChip() {
        Harness().use { h ->
            h.predict()
            h.sms()
            h.dismiss()
            assertFalse(h.bar.isSmsCodeSuggestionActive())
            assertTrue(h.candidates.view.isShown)
            h.bar.onStartInput(EditorInfo(), CapabilityFlags(0uL))
            h.settle()
            assertFalse(h.bar.isSmsCodeSuggestionActive())
            h.sms("8361")
            h.assertSmsShown("8361")
        }
    }

    @Test fun expiryReleasesThePredictionSurfaceWithoutAnotherKeyboardAction() {
        Harness().use { h ->
            h.predict()
            h.sms(age = VerificationCodes.TTL_MS - 1_000L)
            h.assertSmsShown()
            val code = requireNotNull(VerificationCodes.fresh())
            h.settle(1_100L)
            // Paused Looper advances Android uptime, not the JVM wall clock used by the cache.
            // Check cache age with explicit wall time; the following assertions exercise the
            // real coroutine timeout and actual toolbar visibility without another input event.
            assertNull(VerificationCodes.fresh(now = code.timestamp + VerificationCodes.TTL_MS + 1L))
            assertFalse(h.bar.isSmsCodeSuggestionActive())
            assertFalse(h.chip().isShown)
            assertTrue(h.candidates.view.isShown)
        }
    }

    @Test fun smsPreferenceOffClearsTheChipAndOnReplaysOnlyTheFreshCode() {
        Harness().use { h ->
            h.predict()
            h.sms()
            val pref = AppPrefs.getInstance().clipboard.verificationCodeFromSms
            pref.setValue(false)
            h.settle()
            assertFalse(h.bar.isSmsCodeSuggestionActive())
            assertFalse(h.chip().isShown)
            assertTrue(h.candidates.view.isShown)
            pref.setValue(true)
            h.settle()
            h.assertSmsShown()
        }
    }

    @Test fun permissionGateAndPermissionGrantNotificationUseTheFreshMemoryCache() {
        shadowOf(application).denyPermissions(Manifest.permission.RECEIVE_SMS)
        Harness().use { h ->
            h.predict()
            h.sms()
            assertFalse(h.bar.isSmsCodeSuggestionActive())
            assertTrue(h.candidates.view.isShown)
            shadowOf(application).grantPermissions(Manifest.permission.RECEIVE_SMS)
            AppPrefs.getInstance().clipboard.verificationCodeFromSms.fireChange()
            h.settle()
            h.assertSmsShown()
        }
    }

    @Test fun codeIsConsumedOnlyAfterTheActualEditorAcceptsTheTap() {
        Harness().use { h ->
            h.sms()
            h.editor.acceptsCommit = false
            h.tapCode()
            assertEquals("482913", VerificationCodes.fresh()?.code)
            assertEquals("", h.editor.buffer.toString())
            h.assertSmsShown()
            h.editor.acceptsCommit = true
            h.tapCode()
            assertEquals("482913", h.editor.buffer.toString())
            assertNull(VerificationCodes.fresh())
            assertFalse(h.bar.isSmsCodeSuggestionActive())
            assertFalse(h.chip().isShown)
        }
    }

    @Test fun detachedToolbarDoesNotRespondToLaterCodesOrPreferenceNotifications() {
        Harness().use { h ->
            h.bar.dispose()
            h.sms()
            AppPrefs.getInstance().clipboard.verificationCodeFromSms.fireChange()
            h.settle()
            assertFalse(h.bar.isSmsCodeSuggestionActive())
            assertFalse(allViews(h.bar.view).filterIsInstance<TextView>().any {
                it.isShown && it.text.toString().startsWith("短信验证码")
            })
        }
    }

    @Test fun aQueuedBackgroundSmsCannotAccessTheDestroyedDependencyScope() {
        Harness().use { h ->
            val delivery = Thread {
                VerificationCodes.publish("8361", VerificationCodes.Source.Sms)
            }
            delivery.start()
            delivery.join()
            h.bar.dispose()
            h.settle()
            assertFalse(h.bar.isSmsCodeSuggestionActive())
            assertFalse(allViews(h.bar.view).filterIsInstance<TextView>().any {
                it.isShown && it.text.toString().startsWith("短信验证码")
            })
        }
    }
}
