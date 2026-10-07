/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import android.app.Application
import android.content.Context
import android.os.UserManager
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.typingtest.TypingTestCalibrationSample
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PinyinPromptCalibrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val cells = listOf(KeyCell('b', 0f, 0f, 100f, 140f), KeyCell('n', 100f, 0f, 200f, 140f))
    private var previousPrefs: Any? = null
    private var previousApplication: Any? = null

    @Before fun setup() {
        val applicationField = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = applicationField.get(null)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        applicationField.set(null, app)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        PinyinTouchProfileStore.clear(context)
        val preferences = context.getSharedPreferences("prompt-calibration-regression", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true; previousPrefs = get(null); set(null, null)
        }
        AppPrefs.init(preferences)
        AppPrefs.getInstance().keyboard.pinyinTouchPersonalization.setValue(true)
    }

    @After fun restore() {
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, previousPrefs)
        }
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, previousApplication)
        }
    }

    private fun samples(receipt: String? = "a".repeat(64), count: Int = 8) = List(count) { index ->
        TypingTestCalibrationSample(index, 'b', 'n', PinyinTouchProfile.layoutSignature(cells, 1f)!!,
            "portrait", "unknown", -.54, .05, receipt)
    }

    @Test fun `only an explicit confirmed receipt can import a known test batch once`() = runBlocking {
        val result = PinyinPromptCalibration.applyConfirmedSamples(context, samples())
        assertEquals(PinyinPromptCalibration.Result(8, 0, PinyinPromptCalibration.Reason.Applied), result)
        val store = PinyinTouchProfileStore(context)
        assertEquals(8, store.confirmedSampleCount(cells, 1f, 'n'))
        assertEquals(setOf('n'), store.offsets(cells, 1f).keys)
        assertEquals(PinyinPromptCalibration.Result(0, 8, PinyinPromptCalibration.Reason.AlreadyApplied),
            PinyinPromptCalibration.applyConfirmedSamples(context, samples()))
        assertEquals(8, store.confirmedSampleCount(cells, 1f, 'n'))
    }

    @Test fun `five then twenty completed trials do not reimport the first confirmed group`() = runBlocking {
        assertEquals(8, PinyinPromptCalibration.applyConfirmedSamples(context, samples()).appliedCount)
        val expanded = samples() + samples("b".repeat(64), 1)
        assertEquals(PinyinPromptCalibration.Result(1, 8, PinyinPromptCalibration.Reason.Applied),
            PinyinPromptCalibration.applyConfirmedSamples(context, expanded))
        assertEquals(9, PinyinTouchProfileStore(context).confirmedSampleCount(cells, 1f, 'n'))
    }

    @Test fun `disabled personalization and unlabeled legacy samples cannot silently train`() = runBlocking {
        assertEquals(PinyinPromptCalibration.Reason.Invalid,
            PinyinPromptCalibration.applyConfirmedSamples(context, samples(null)).reason)
        AppPrefs.getInstance().keyboard.pinyinTouchPersonalization.setValue(false)
        assertEquals(PinyinPromptCalibration.Reason.Disabled,
            PinyinPromptCalibration.applyConfirmedSamples(context, samples()).reason)
        assertEquals(0, PinyinTouchProfileStore(context).confirmedSampleCount(cells, 1f, 'n'))
    }

    @Test fun `bad labels duplicate contact indices and far coordinates reject the whole batch`() = runBlocking {
        val valid = samples()
        for (invalid in listOf(valid + valid.first(), valid.map { it.copy(normalizedOffsetX = .7) },
            valid.map { it.copy(confirmationId = "私人拼音") }, valid.map { it.copy(hand = "predicted") })) {
            assertEquals(PinyinPromptCalibration.Reason.Invalid,
                PinyinPromptCalibration.applyConfirmedSamples(context, invalid).reason)
        }
        assertEquals(0, PinyinTouchProfileStore(context).confirmedSampleCount(cells, 1f, 'n'))
    }

    @Test fun `a neighboring key swap cannot become two training substitutions`() = runBlocking {
        val first = samples(count = 1).single()
        val swap = listOf(first.copy(originalKey = 'n', intendedKey = 'b', normalizedOffsetX = .52),
            first.copy(inputIndex = 1, originalKey = 'b', intendedKey = 'n', normalizedOffsetX = -.52))
        assertEquals(PinyinPromptCalibration.Reason.Invalid,
            PinyinPromptCalibration.applyConfirmedSamples(context, swap).reason)
        val store = PinyinTouchProfileStore(context)
        assertEquals(0, store.confirmedSampleCount(cells, 1f, 'b'))
        assertEquals(0, store.confirmedSampleCount(cells, 1f, 'n'))
    }
}
