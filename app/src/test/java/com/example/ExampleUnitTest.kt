package com.example

import com.example.continuity.SensitiveDataDetector
import com.example.transfer.FileTransferEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `sensitive data detector blocks OTP keys and passwords`() {
        assertTrue(SensitiveDataDetector.isSensitiveText("Your verification code is 492018"))
        assertTrue(SensitiveDataDetector.isSensitiveText("AIzaSyD-ExampleKey1234567890abcdef"))
        assertTrue(SensitiveDataDetector.isSensitiveText("sk-proj1234567890abcdef"))
        assertTrue(SensitiveDataDetector.isSensitiveText("Bearer eyJhbGciOiJIUzI1NiJ9.token"))
        assertTrue(SensitiveDataDetector.isSensitiveText("password=supersecret"))
    }

    @Test
    fun `sensitive data detector allows benign urls and text`() {
        assertFalse(SensitiveDataDetector.isSensitiveText("https://developer.android.com/compose"))
        assertFalse(SensitiveDataDetector.isSensitiveText("Meeting notes: review Phase 2 roadmap at 3pm"))
        assertFalse(SensitiveDataDetector.isSensitiveText(""))
    }

    @Test
    fun `file size formatting produces readable units`() {
        assertEquals("512 B", FileTransferEngine.formatBytes(512L))
        assertEquals("2.0 KB", FileTransferEngine.formatBytes(2048L))
        assertEquals("1.5 MB", FileTransferEngine.formatBytes((1.5 * 1024 * 1024).toLong()))
    }
}
