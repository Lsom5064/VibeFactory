package kr.ac.kangwon.hai.vibefactory

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.ac.kangwon.hai.vibefactory.ui_editor.UiAnnotationEditorActivity
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HostNetworkPerformanceInstrumentedTest {
    @Test
    fun compressedServerReadsKeepDtoAndIncrementalTimelineContracts() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("performanceServerUrl")
        assumeTrue(url == "http://127.0.0.1:18092/")
        val client = createVibeHttpClient()
        val api = Retrofit.Builder().baseUrl(url!!).client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(VibeApiService::class.java)
        val status = api.getStatus("xml-editor-task", deviceId = "xml-editor-device", userId = null,
            phoneNumber = null)
        assertEquals("Success", status.status)
        assertTrue((status.timeline_events?.asJsonArray?.size() ?: 0) > 0)
        val next = api.getStatus("xml-editor-task", deviceId = "xml-editor-device", userId = null,
            phoneNumber = null, timelineAfterEventId = status.timeline_cursor)
        assertEquals(0, next.timeline_events?.asJsonArray?.size() ?: 0)
        val path = "tasks/xml-editor-task/revisions/rev_0001/ui/layouts/activity_main?device_id=xml-editor-device"
        fun request(identity: Boolean): Pair<String, Long> {
            val builder = Request.Builder().url(url + path)
            if (identity) builder.header("Accept-Encoding", "identity")
            return client.newCall(builder.build()).execute().use { response ->
                assertEquals(200, response.code)
                val wireBytes = response.networkResponse!!.header("Content-Length")!!.toLong()
                if (!identity) assertEquals("gzip", response.networkResponse!!.header("Content-Encoding"))
                response.body!!.string() to wireBytes
            }
        }
        val compressed = request(false)
        val identity = request(true)
        assertEquals(identity.first, compressed.first)
        assertTrue(compressed.second < identity.second)
        println("UI_LAYOUT_WIRE_BYTES=${identity.second}->${compressed.second}")
    }

    @Test
    fun editorStartsLayoutAndDraftReadsTogetherUsingExistingLogin() {
        val arguments = InstrumentationRegistry.getArguments()
        val task = arguments.getString("performanceTaskId")
        assumeTrue(!task.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val started = CountDownLatch(2)
        val api = createVibeApiService()
        val observedApi = Proxy.newProxyInstance(VibeApiService::class.java.classLoader,
            arrayOf(VibeApiService::class.java)) { _, method, parameters ->
            if (method.name == "getRevisionUiLayout" || method.name == "getUiEditorDraft") {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                started.countDown()
                assertTrue("Independent editor reads were serialized", started.await(5, TimeUnit.SECONDS))
            }
            try {
                method.invoke(api, *(parameters ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        } as VibeApiService
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is UiAnnotationEditorActivity) {
                    // Observe the real Activity's reads without replacing the server or user identity.
                    activity.javaClass.getDeclaredField("apiService\$delegate").apply {
                        isAccessible = true
                        set(activity, lazy { observedApi })
                    }
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            val intent = Intent(context, UiAnnotationEditorActivity::class.java)
                .putExtra(UiAnnotationEditorActivity.EXTRA_TASK_ID, task)
                .putExtra(UiAnnotationEditorActivity.EXTRA_REVISION_LABEL, arguments.getString("performanceRevision"))
            val startedAt = SystemClock.elapsedRealtime()
            ActivityScenario.launch<UiAnnotationEditorActivity>(intent).use { scenario ->
                var rendered = false
                val deadline = startedAt + 30_000
                while (!rendered && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        rendered = activity.findViewById<ViewGroup>(R.id.uiAnnotationCanvas).childCount > 0 &&
                            activity.findViewById<View>(R.id.uiAnnotationStateOverlay).visibility == View.GONE
                    }
                    if (!rendered) SystemClock.sleep(50)
                }
                assertEquals(0L, started.count)
                assertTrue("Actual server layout did not render", rendered)
                println("UI_LIVE_EDITOR_LOAD_MS=${SystemClock.elapsedRealtime() - startedAt}")
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}
