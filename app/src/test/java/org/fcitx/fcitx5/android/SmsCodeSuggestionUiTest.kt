/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.text.Editable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.otp.VerificationCodes
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ClipboardSuggestionDismissals
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
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
        clearSmsDismissal()
    }

    @After fun restore() {
        VerificationCodes.consume()
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

    private inner class Harness : AutoCloseable {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        private val scope = DynamicScope()
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val editor = Editor(application)
        val bar = KawaiiBarComponent()
        val candidates = HorizontalCandidateComponent()
        private val theme: Theme = ThemePreset.XuancaiBlackV09
        private val uiContext: ContextThemeWrapper = ContextThemeWrapper(activity, R.style.Theme_InputViewTheme)

        init {
            service.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            service.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            ReflectionHelpers.setField(service, "mStartedInputConnection", editor)
            ReflectionHelpers.getField<CursorTracker>(service, "selection").resetTo(0)
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
            settle()
        }

        fun settle(milliseconds: Long = 16L) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
            repeat(2) {
                bar.view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(52, View.MeasureSpec.EXACTLY))
                bar.view.layout(0, 0, 360, 52)
            }
        }

        fun predict(token: Long = 1L) {
            candidates.setPredictionOffer(NextWordPredictionOffer(token, listOf("快乐", "朋友")))
            bar.setNextWordPredictionVisible(true)
            settle()
        }

        fun sms(code: String = "482913", age: Long = 0L) {
            VerificationCodes.publish(code, VerificationCodes.Source.Sms, System.currentTimeMillis() - age)
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
