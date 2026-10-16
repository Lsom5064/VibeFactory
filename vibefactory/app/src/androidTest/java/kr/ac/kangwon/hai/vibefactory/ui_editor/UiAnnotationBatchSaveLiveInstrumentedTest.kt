package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kr.ac.kangwon.hai.vibefactory.HostPreferencesStore
import kr.ac.kangwon.hai.vibefactory.R
import kr.ac.kangwon.hai.vibefactory.VibeApiService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Opt-in actual editor-to-server save; caller must supply a task in an isolated server/database. */
@RunWith(AndroidJUnit4::class)
class UiAnnotationBatchSaveLiveInstrumentedTest {
    @Test fun saveButtonConfirmsEveryScreenIncludingItsSketchThroughTheBatchApi() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val task = arguments.getString("multiscreenTaskId").orEmpty()
        val url = arguments.getString("multiscreenServerUrl").orEmpty()
        assumeTrue(task.startsWith("isolated_multiscreen_") && url == "http://127.0.0.1:18016")
        val context = instrumentation.targetContext
        val gson = Gson()
        val preferences = HostPreferencesStore(context, gson, "MultiScreenLiveTest")
        val device = preferences.getOrCreateDeviceId()
        val phone = preferences.loadPhoneNumber()
        val api = Retrofit.Builder().baseUrl(url).addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(VibeApiService::class.java)
        val layouts = api.getRevisionUiLayouts(task, "rev_0001", device, null, phone).layouts
        assertEquals(7, layouts.size)
        val store = UiAnnotationDraftStore(context, gson)
        val model = UiAnnotationViewModel()
        layouts.forEachIndexed { index, layout ->
            val source = api.getRevisionUiLayout(task, "rev_0001", layout.layout_name,
                layout.configuration, device, null, phone)
            val document = AndroidXmlDocument.parse(source.xml, source.sha256)
            val session = model.initialize(task, "rev_0001", layout, document,
                ResolvedUiResources.from(source.resource_files), 0, emptyList(), emptySet(), emptySet(), null)
            val target = UiAnnotationTarget("id:title", "@+id/title", "0.0", "TextView", "화면 제목", "",
                UiNormalizedRect(0f, 0f, 1f, .1f), "", "")
            val annotation = if (index == 0) UiAnnotation(action = UiAnnotationAction.ADD, target = target,
                instruction = "오늘 날짜로 돌아갈 수 있는 버튼", addition = UiAdditionSpec(
                    UiNormalizedRect(.1f, .2f, .9f, .35f), -1,
                    listOf(UiSketchStroke(points = listOf(UiSketchPoint(.1f, .2f), UiSketchPoint(.9f, .8f)),
                        color = -16777216, width = .01f))))
                else UiAnnotation(action = UiAnnotationAction.BEHAVIOR, target = target, instruction = "화면 ${index+1}의 제목을 크게 표시")
            session.annotations += annotation
            if (index == 0) {
                val image = store.persistSketch(session, annotation, 3f)
                session.images += image
                session.annotations[0] = annotation.copy(imageIds = listOf(image.imageId), sketchImageId = image.imageId)
            }
            store.save(store.recordFor(session))
        }
        val intent = Intent(context, UiAnnotationEditorActivity::class.java)
            .putExtra(UiAnnotationEditorActivity.EXTRA_TASK_ID, task)
            .putExtra(UiAnnotationEditorActivity.EXTRA_REVISION_LABEL, "rev_0001")
            .putExtra(UiAnnotationEditorActivity.EXTRA_APP_NAME, "여러 화면 저장 검증")
        ActivityScenario.launch<UiAnnotationEditorActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Redirect this Activity only. Host address and participant preferences stay unchanged.
                UiAnnotationEditorActivity::class.java.getDeclaredField("apiService\$delegate").apply {
                    isAccessible = true
                    set(activity, lazy { api })
                }
                UiAnnotationEditorActivity::class.java.getDeclaredMethod("loadLayouts").apply {
                    isAccessible = true
                    invoke(activity)
                }
            }
            val deadline = SystemClock.uptimeMillis() + 60000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { ready = it.findViewById<View>(R.id.uiAnnotationStateOverlay).visibility == View.GONE &&
                    it.findViewById<Button>(R.id.btnUiAnnotationSave).isEnabled }
                SystemClock.sleep(100)
            }
            assertTrue("All seven local screen drafts should be available to Save", ready)
            scenario.onActivity { it.findViewById<Button>(R.id.btnUiAnnotationSave).performClick() }
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertEquals("The real save workflow must finish after all screens are confirmed", Lifecycle.State.DESTROYED, scenario.state)
        }
        val saved = store.loadAll(task, "rev_0001")
        assertEquals(7, saved.count { it.confirmed })
        assertEquals(7, saved.mapNotNull { it.serverDraftId }.distinct().size)
        assertEquals(1, saved.flatMap { it.images }.count { !it.serverWorkspacePath.isNullOrBlank() })
        println("MULTISCREEN_LIVE_SAVE confirmed=7 sketches=1 participantPreferencesUnchanged=true")
    }
}
