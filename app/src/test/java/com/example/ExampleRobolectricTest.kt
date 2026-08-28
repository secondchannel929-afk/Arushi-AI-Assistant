package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.ActionBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Arushi AI", appName)
  }

  @Test
  fun `test ActionBridge openWhatsApp fallback and execution`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val actionBridge = ActionBridge(context)
    val result = actionBridge.openWhatsApp()
    assertNotNull(result)
    assertEquals("openWhatsApp", result.actionName)
  }

  @Test
  fun `test ActionBridge makeCall with phone digits`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val actionBridge = ActionBridge(context)
    val result = actionBridge.makeCall("9876543210")
    assertNotNull(result)
    assertEquals("makeCall", result.actionName)
    assertTrue(result.success)
  }

  @Test
  fun `test ActionBridge openApp settings and browser`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val actionBridge = ActionBridge(context)
    val settingsRes = actionBridge.openApp("Settings")
    assertNotNull(settingsRes)
    assertTrue(settingsRes.success)

    val chromeRes = actionBridge.openApp("Chrome")
    assertNotNull(chromeRes)
  }

  @Test
  fun `test ActionBridge openUrl`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val actionBridge = ActionBridge(context)
    val res = actionBridge.openUrl("https://google.com")
    assertTrue(res.success)
  }
}

