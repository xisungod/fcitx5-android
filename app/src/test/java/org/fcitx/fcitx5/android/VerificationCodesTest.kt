package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.otp.VerificationCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerificationCodesTest {
    @Test fun findsCodesInRealStyleMessages() {
        assertEquals("482913", VerificationCodes.extract("【招商银行】您的验证码为 482913，5分钟内有效，请勿泄露。"))
        assertEquals("8361", VerificationCodes.extract("验证码：8361。您正在登录，如非本人操作请忽略"))
        assertEquals("325087", VerificationCodes.extract("【美团】325087（登录验证码）。工作人员不会向你索要，请勿向任何人泄露。"))
        assertEquals("920431", VerificationCodes.extract("Your verification code is 920431. It expires in 10 minutes."))
        assertEquals("582913", VerificationCodes.extract("G-582913 is your Google verification code."))
        assertEquals("667788", VerificationCodes.extract("您尾号1234的卡消费120元，验证码 667788，2026年10月4日"))
        assertEquals("A7K9Q2", VerificationCodes.extract("【京东】验证码 A7K9Q2，用于身份验证"))
        assertEquals("90123456", VerificationCodes.extract("动态密码：90123456（10分钟有效）"))
    }

    @Test fun ignoresTextWithoutACode() {
        assertNull(VerificationCodes.extract("今天下午3点开会，地址A座1203"))
        assertNull(VerificationCodes.extract("您的订单已发货，单号 SF1234567890"))
        assertNull(VerificationCodes.extract(""))
    }

    @Test fun keepsOnlyFreshCodes() {
        VerificationCodes.publish("123456", VerificationCodes.Source.Sms, now = 1_000L)
        assertEquals("123456", VerificationCodes.fresh(now = 1_000L + 60_000L)?.code)
        assertNull(VerificationCodes.fresh(now = 1_000L + VerificationCodes.TTL_MS + 1))
        VerificationCodes.consume()
        assertNull(VerificationCodes.fresh(now = 1_001L))
    }
}
