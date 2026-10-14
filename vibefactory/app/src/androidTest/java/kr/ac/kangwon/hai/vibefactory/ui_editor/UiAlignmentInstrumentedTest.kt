package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kr.ac.kangwon.hai.vibefactory.TaskLogDetailActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real touch/render/preview checks in a temporary view. No Task IDs, server writes or participant preference changes. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class UiAlignmentInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val density get() = context.resources.displayMetrics.density
    private lateinit var canvas: FrameLayout
    private lateinit var overlay: UiAnnotationOverlayView
    private lateinit var preview: UiMovePreviewLayout
    private lateinit var source: UiAnnotationTarget
    private lateinit var peer: UiSnapTarget
    private var pendingMove: UiAnnotation? = null
    private var pendingAddition: UiAnnotation? = null
    private var moveSource: UiAnnotationTarget? = null
    private var activeTouch: Triple<Long, Float, Float>? = null

    private fun fixture(block: () -> Unit) {
        val ready = CountDownLatch(1)
        // Empty log payload does not query a Task; the temporary content is removed when this activity closes.
        ActivityScenario.launch<TaskLogDetailActivity>(Intent(context, TaskLogDetailActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                canvas = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                activity.addContentView(canvas, FrameLayout.LayoutParams(-1, -1))
                val document = AndroidXmlDocument.parse("""
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent" android:layout_height="match_parent"
                        android:orientation="vertical" android:padding="16dp" android:background="#FFFFFF">
                        <LinearLayout android:layout_width="match_parent" android:layout_height="64dp" android:orientation="horizontal">
                            <Button android:id="@+id/peer" android:layout_width="80dp" android:layout_height="64dp"
                                android:text="기준 UI" android:minWidth="0dp" android:minHeight="0dp" />
                            <Button android:id="@+id/second" android:layout_width="80dp" android:layout_height="64dp"
                                android:layout_marginStart="8dp" android:text="주변 UI" android:minWidth="0dp" android:minHeight="0dp" />
                        </LinearLayout>
                        <Button android:id="@+id/source" android:layout_width="100dp" android:layout_height="64dp"
                            android:layout_marginTop="100dp" android:text="이동할 UI" android:minWidth="0dp" android:minHeight="0dp" />
                    </LinearLayout>
                """.trimIndent())
                val rendered = UiPreviewRenderer(activity).render(document, ResolvedUiResources.EMPTY, canvas)
                overlay = UiAnnotationOverlayView(activity)
                canvas.addView(overlay, FrameLayout.LayoutParams(-1, -1))
                canvas.post {
                    preview = UiMovePreviewLayout(canvas, document, rendered.nodeViews)
                    overlay.setReferenceSize(canvas.width, canvas.height)
                    fun target(id: String): UiAnnotationTarget {
                        val node = document.root.descendantsAndSelf().first { it.androidAttribute("id") == "@+id/$id" }
                        val base = UiAnnotationTarget(node.stableId, node.androidAttribute("id").orEmpty(), node.elementPath.joinToString("."),
                            node.tagName, id, "", UiNormalizedRect(0f, 0f, 0f, 0f), "", "")
                        return base.copy(bounds = preview.displayBounds(base))
                    }
                    source = target("source")
                    peer = preview.alignmentTargets(emptyList(), emptyList(), null).first { it.id == target("peer").stableId }
                    overlay.alignmentTargetsProvider = { preview.alignmentTargets(emptyList(), emptyList(), moveSource) }
                    overlay.alignmentTargetBoundsProvider = preview::alignmentTargetBounds
                    overlay.moveSourceTouchListener = { x, y ->
                        if (UiAdditionGeometry.contains(source.bounds, x, y)) {
                            moveSource = source
                            overlay.showPendingMove(source)
                            true
                        } else false
                    }
                    overlay.movePreviewListener = { x, y ->
                        pendingMove = if (x != null && y != null) UiAnnotation(annotationId = "pending", action = UiAnnotationAction.MOVE,
                            target = source, destinationX = x, destinationY = y) else null
                        preview.apply(listOfNotNull(pendingMove), false)
                        overlay.showMoveDestinationBounds(emptyMap(), pendingMove?.let { preview.destinationBounds[it.annotationId] })
                    }
                    overlay.destinationTapListener = { _, _ -> }
                    overlay.additionBoundsChanged = { bounds, _ ->
                        pendingAddition = pendingAddition?.copy(addition = pendingAddition!!.addition!!.copy(bounds = bounds))
                        preview.apply(emptyList(), false, listOfNotNull(pendingAddition))
                    }
                    ready.countDown()
                }
            }
            assertTrue("Preview must finish layout", ready.await(15, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            awaitFrame()
            try { block() } finally {
                activeTouch?.let { (down, x, y) -> runCatching { send(down, MotionEvent.ACTION_CANCEL, x, y) } }
            }
        }
    }

    @Test fun moveSnapsAndReleasesWhileSourceAndSavedCoordinatesStayStable() = fixture {
        instrumentation.runOnMainSync { overlay.enableTargetSelection(true) }
        val origin = source.bounds
        val sx = (origin.left + origin.right) * canvas.width / 2
        val sy = (origin.top + origin.bottom) * canvas.height / 2
        val centerX = canvas.width - 62f * density
        val halfHeight = (origin.bottom - origin.top) * canvas.height / 2
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, sx, sy)
        send(down, MotionEvent.ACTION_MOVE, centerX, peer.box.top + halfHeight + 4f * density)
        assertEquals((peer.box.top + halfHeight) / canvas.height, pendingMove!!.destinationY!!, .0001f)
        assertEquals(origin, preview.displayBounds(source))
        assertTrue("Alignment guide must be drawn while dragging", screenshot("move_snapped") > 20)
        send(down, MotionEvent.ACTION_MOVE, centerX, peer.box.top + halfHeight + 11f * density)
        assertEquals((peer.box.top + halfHeight) / canvas.height, pendingMove!!.destinationY!!, .0001f)
        send(down, MotionEvent.ACTION_MOVE, centerX, peer.box.top + halfHeight + 17f * density)
        assertEquals((peer.box.top + halfHeight + 17f * density) / canvas.height, pendingMove!!.destinationY!!, .0001f)
        send(down, MotionEvent.ACTION_MOVE, centerX, peer.box.top + halfHeight + 4f * density)
        send(down, MotionEvent.ACTION_UP, centerX, peer.box.top + halfHeight + 4f * density)
        assertEquals(0, screenshot("move_released"))
        val saved = preview.resolvedMove(pendingMove!!)
        val xml = UiAnnotationXmlCodec.encode("isolated", "rev_0001", "activity_main", "xml", "sha", listOf(saved))
        val restored = UiAnnotationXmlCodec.decode(xml).single()
        assertEquals(saved.annotationId, restored.annotationId)
        assertEquals(saved.target.stableId, restored.target.stableId)
        // Existing XML coordinates use six decimal places; this is less than one physical pixel.
        assertEquals(saved.destinationX!!, restored.destinationX!!, .000001f)
        assertEquals(saved.destinationY!!, restored.destinationY!!, .000001f)
        assertEquals(saved.destinationWidth!!, restored.destinationWidth!!, .000001f)
        assertEquals(saved.equalWidthRow == true, restored.equalWidthRow == true)
    }

    @Test fun additionDragKeepsSizeAndDoesNotChaseDisplacedNeighbours() = fixture {
        showAddition(UiPreviewBox(200f * density, 300f * density, 280f * density, 364f * density))
        val start = pendingAddition!!.addition!!.bounds
        val centerX = (start.left + start.right) * canvas.width / 2
        val centerY = (start.top + start.bottom) * canvas.height / 2
        val destinationX = peer.box.left + 50f * density
        val destinationY = peer.box.top + 32f * density + 4f * density
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, centerX, centerY)
        send(down, MotionEvent.ACTION_MOVE, destinationX, destinationY)
        val once = pendingAddition!!.addition!!.bounds
        assertEquals(peer.box.top / canvas.height, once.top, .0001f)
        assertEquals(start.right - start.left, once.right - once.left, .0001f)
        assertEquals(start.bottom - start.top, once.bottom - once.top, .0001f)
        send(down, MotionEvent.ACTION_MOVE, destinationX, destinationY)
        assertEquals(once, pendingAddition!!.addition!!.bounds)
        assertTrue(screenshot("addition_aligned") > 20)
        send(down, MotionEvent.ACTION_UP, destinationX, destinationY)
        assertEquals(0, screenshot("addition_released"))
    }

    @Test fun additionCornerMatchesSizeAndXmlStoresWhatWasDrawn() = fixture {
        showAddition(UiPreviewBox(200f * density, 300f * density, 330f * density, 410f * density))
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, 330f * density, 410f * density)
        send(down, MotionEvent.ACTION_MOVE, 283f * density, 367f * density)
        val b = pendingAddition!!.addition!!.bounds
        assertEquals(80f * density, (b.right - b.left) * canvas.width, .01f)
        assertEquals(64f * density, (b.bottom - b.top) * canvas.height, .01f)
        assertEquals(200f * density, b.left * canvas.width, .01f)
        assertEquals(300f * density, b.top * canvas.height, .01f)
        assertTrue("Same-size labels must be drawn", screenshot("addition_same_size") > 100)
        send(down, MotionEvent.ACTION_UP, 283f * density, 367f * density)
        assertEquals(0, screenshot("addition_size_released"))
        val annotation = pendingAddition!!.copy(instruction = "새 UI를 추가해 줘")
        val xml = UiAnnotationXmlCodec.encode("isolated", "rev_0001", "activity_main", "xml", "sha", listOf(annotation),
            referenceCanvasWidthDp = canvas.width / density, referenceCanvasHeightDp = canvas.height / density)
        assertEquals(annotation.addition!!.bounds, UiAnnotationXmlCodec.decode(xml).single().addition!!.bounds)
        File(context.getExternalFilesDir(null), "alignment_test_20261014/addition.xml").writeText(xml)
    }

    private fun showAddition(box: UiPreviewBox) = instrumentation.runOnMainSync {
        pendingAddition = UiAnnotation(action = UiAnnotationAction.ADD, target = source, addition = UiAdditionSpec(
            UiNormalizedRect(box.left / canvas.width, box.top / canvas.height, box.right / canvas.width, box.bottom / canvas.height), Color.WHITE))
        moveSource = null
        overlay.showPendingMove(null)
        overlay.showAdditionPlacement(pendingAddition)
        preview.apply(emptyList(), false, listOf(pendingAddition!!))
    }

    private fun send(down: Long, action: Int, x: Float, y: Float) {
        val location = IntArray(2)
        instrumentation.runOnMainSync { overlay.getLocationOnScreen(location) }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + location[0], y + location[1], 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        check(automation.injectInputEvent(event, true))
        event.recycle()
        activeTouch = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) null else Triple(down, x, y)
        instrumentation.waitForIdleSync()
        awaitFrame()
    }

    private fun awaitFrame() {
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            overlay.viewTreeObserver.registerFrameCommitCallback { drawn.countDown() }
            overlay.invalidate()
        }
        assertTrue("Touch result must reach the rendered frame", drawn.await(5, TimeUnit.SECONDS))
    }

    private fun screenshot(name: String): Int {
        awaitFrame()
        val bitmap = checkNotNull(automation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "alignment_test_20261014").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var magentaPixels = 0
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val color = bitmap.getPixel(x, y)
            if (Color.red(color) in 180..210 && Color.green(color) in 20..65 && Color.blue(color) in 150..190) magentaPixels++
        }
        bitmap.recycle()
        return magentaPixels
    }
}
