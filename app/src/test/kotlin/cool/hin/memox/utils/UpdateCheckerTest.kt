package cool.hin.memox.utils

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import cool.hin.memox.BuildConfig
import cool.hin.memox.R
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateCheckerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val required = UpdateChecker.UpdateInfo(
        "1.3.0", 13000, "更新说明", "https://example.com/releases",
        "https://example.com/memox.apk", true, true, "major",
    )

    @Before fun setUp() {
        context.getSharedPreferences("version_update", Context.MODE_PRIVATE).edit().clear().commit()
        UpdateChecker.state.value = null
        mockkObject(UpdateChecker)
        // 自动检查不访问公网；HTTP 契约在下面使用本地服务器验证。
        every { UpdateChecker.checkForUpdates(any()) } just Runs
    }

    @After fun tearDown() {
        unmockkObject(UpdateChecker)
        UpdateChecker.state.value = null
        context.getSharedPreferences("version_update", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun requiredDialogSurvivesDownloadBackAndRecreation() {
        UpdateChecker.rememberResult(context, required)
        val controller = Robolectric.buildActivity(UpdateTestActivity::class.java).setup()
        try {
            val activity = controller.get()
            val fragment = activity.supportFragmentManager.findFragmentByTag(UpdateDialogFragment.TAG)
                as UpdateDialogFragment
            val dialog = fragment.requireDialog() as AlertDialog
            assertFalse(fragment.isCancelable)
            assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(required.downloadUrl, shadowOf(activity).nextStartedActivity.dataString)
            assertTrue(dialog.isShowing)
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            assertTrue(dialog.isShowing)

            controller.recreate()
            val restored = controller.get().supportFragmentManager
                .findFragmentByTag(UpdateDialogFragment.TAG) as UpdateDialogFragment
            assertFalse(restored.isCancelable)
            assertTrue(restored.requireDialog().isShowing)
            // 服务端撤销强制更新后无需重启即可解除阻挡。
            UpdateChecker.rememberResult(context, required.copy(forceUpdate = false))
            assertNull(controller.get().supportFragmentManager.findFragmentByTag(UpdateDialogFragment.TAG))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun optionalUpdateCanBeDeferredAndUsesReleaseFallback() {
        val optional = required.copy(downloadUrl = null, forceUpdate = false, updateType = "minor")
        UpdateChecker.rememberResult(context, optional)
        val controller = Robolectric.buildActivity(UpdateTestActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertNull(activity.supportFragmentManager.findFragmentByTag(UpdateDialogFragment.TAG))
            UpdateChecker.showUpdateDialog(activity, optional)
            val fragment = activity.supportFragmentManager.findFragmentByTag(UpdateDialogFragment.TAG)
                as UpdateDialogFragment
            assertTrue(fragment.isCancelable)
            val dialog = fragment.requireDialog() as AlertDialog
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertTrue(UpdateChecker.openUpdate(activity, optional))
            assertEquals(optional.releaseUrl, shadowOf(activity).nextStartedActivity.dataString)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun mandatoryCacheSurvivesRestartAndExpiresForAnotherInstalledBuild() {
        UpdateChecker.rememberResult(context, required)
        UpdateChecker.state.value = null
        UpdateChecker.restoreRequiredUpdate(context)
        assertEquals(required, UpdateChecker.state.value)

        context.getSharedPreferences("version_update", Context.MODE_PRIVATE).edit()
            .putString("installed_build", "older-build").commit()
        UpdateChecker.state.value = null
        UpdateChecker.restoreRequiredUpdate(context)
        assertNull(UpdateChecker.state.value)

        UpdateChecker.rememberResult(context, required)
        UpdateChecker.rememberResult(context, required.copy(forceUpdate = false))
        UpdateChecker.state.value = null
        UpdateChecker.restoreRequiredUpdate(context)
        assertNull(UpdateChecker.state.value)
    }

    @Test fun requestUsesNormalizedVersionAndRejectsHTTPFailures() {
        val version = UpdateProtocol.currentVersion(BuildConfig.VERSION_NAME)
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val requestLine = AtomicReference<String>()
        val executor = Executors.newSingleThreadExecutor()
        val handler = executor.submit {
            repeat(2) { index ->
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    requestLine.set(reader.readLine())
                    while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                    val body = UpdateProtocol.encode(required, version).toByteArray(Charsets.UTF_8)
                    val status = if (index == 0) "200 OK" else "503 Service Unavailable"
                    socket.getOutputStream().use { output ->
                        output.write(
                            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII),
                        )
                        output.write(body)
                    }
                }
            }
        }
        try {
            val endpoint = "http://127.0.0.1:${server.localPort}/check"
            assertEquals(required, UpdateChecker.fetch(endpoint))
            assertEquals("GET /check?currentVersion=$version HTTP/1.1", requestLine.get())
            UpdateChecker.rememberResult(context, required)
            assertTrue(runCatching { UpdateChecker.fetch(endpoint) }.isFailure)
            assertEquals(required, UpdateChecker.state.value)
            handler.get(5, TimeUnit.SECONDS)
            assertEquals(
                "https://glaze.cc.cd/api/v1/update/check",
                context.getString(R.string.update_check_url),
            )
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    class UpdateTestActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.AppTheme)
            super.onCreate(savedInstanceState)
        }
        override fun onPostCreate(savedInstanceState: Bundle?) {
            super.onPostCreate(savedInstanceState)
            UpdateChecker.observe(this)
        }
        override fun onPostResume() {
            super.onPostResume()
            UpdateChecker.onActivityResumed(this)
        }
    }
}
