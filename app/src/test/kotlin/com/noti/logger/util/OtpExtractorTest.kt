package com.noti.logger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpExtractorTest {

    @Test fun codeAfterKeyword() {
        assertEquals("458213", OtpExtractor.extract("Your OTP is 458213, valid for 10 minutes."))
        assertEquals("4521", OtpExtractor.extract("4521 is your verification code."))
        assertEquals("903214", OtpExtractor.extract("Use code 903214 to log in."))
    }

    @Test fun standaloneOnlyWhenMessageLooksLikeOtp() {
        // Has a keyword ("verification") but the code isn't adjacent — still found.
        assertEquals("123456", OtpExtractor.extract("For verification, enter this: 123456 now"))
    }

    @Test fun ignoresNumbersInOrdinaryMessages() {
        assertNull(OtpExtractor.extract("AED 4500 was debited from your account ending 1234."))
        assertNull(OtpExtractor.extract("Call me back on 5551234 when free."))
    }

    @Test fun ignoresTooShortOrTooLong() {
        assertNull(OtpExtractor.extract("Your code is 12"))          // 2 digits
        assertEquals("12345678", OtpExtractor.extract("OTP 12345678")) // 8 digits ok
        assertNull(OtpExtractor.extract("code 1234567890"))          // 10-digit run rejected
    }

    @Test fun noDigits() {
        assertNull(OtpExtractor.extract("Your package has been delivered."))
    }
}
