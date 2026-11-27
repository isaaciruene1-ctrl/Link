package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LinkDatabase
import com.example.data.local.TrustedDeviceEntity
import com.example.input.MultiControlUiState
import com.example.ui.FeatureStatusCatalog
import com.example.ui.components.MultiControlDockCard
import com.example.ui.screens.AuthScreen
import com.example.ui.theme.LinkTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app branding and firestore database config resources are populated`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("LINK", context.getString(R.string.app_name))
        val dbId = context.getString(R.string.firestore_database_id)
        assertTrue("Expected non-blank firestore_database_id", dbId.isNotBlank())
    }

    @Test
    fun `room trusted device database insert query with relay secret and revoke`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LinkDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.trustedDeviceDao()
            val device = TrustedDeviceEntity(
                deviceId = "lnk_testdevice1234567890",
                displayName = "Pixel Tablet",
                manufacturer = "Google",
                model = "Pixel Tablet",
                deviceType = "TABLET",
                publicKeyBase64 = "TEST_PUB_KEY",
                fingerprintShort = "ABCD-1234-EF56-7890",
                relaySecretBase64 = "AAAAAAAAAAAAAAAAAAAAAA=="
            )
            dao.upsert(device)
            val loaded = dao.getById(device.deviceId)
            assertNotNull(loaded)
            assertEquals("Pixel Tablet", loaded?.displayName)
            assertEquals("AAAAAAAAAAAAAAAAAAAAAA==", loaded?.relaySecretBase64)

            dao.deleteById(device.deviceId)
            assertNull(dao.getById(device.deviceId))
        } finally {
            db.close()
        }
    }

    @Test
    fun `feature status catalog includes phase 3 to 5 modules`() {
        val modules = FeatureStatusCatalog.items.map { it.module }
        assertTrue(modules.any { it.contains("Wi-Fi Direct") })
        assertTrue(modules.any { it.contains("Cloud Remote Relay") })
        assertTrue(modules.any { it.contains("H.264") })
        assertTrue(modules.any { it.contains("Multi-Control") })
    }

    @Test
    fun `auth screen renders google sign in button`() {
        composeRule.setContent {
            LinkTheme {
                AuthScreen(onAuthSuccess = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("google_sign_in_button")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `multi control dock renders receiver arming switch and touchpad`() {
        composeRule.setContent {
            LinkTheme {
                MultiControlDockCard(
                    connectedPeers = emptyList(),
                    multiControlState = MultiControlUiState(),
                    accessibilityConnected = false,
                    onSetReceiverArmed = {},
                    onSendTap = { _, _, _ -> },
                    onSendSwipe = { _, _, _, _, _ -> },
                    onSendNav = { _, _ -> },
                    onSendText = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("input_receiver_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("multicontrol_touchpad").assertIsDisplayed()
    }
}
