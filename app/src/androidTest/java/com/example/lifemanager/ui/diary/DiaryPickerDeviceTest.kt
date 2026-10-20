package com.example.lifemanager.ui.diary

import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.VideoView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.MainActivity
import com.example.lifemanager.data.diary.createDiaryDeviceVideo
import java.io.File
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Native platform picker and actual production VideoView. Run only on the owned API35 AVD. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class DiaryPickerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun platformPickerImportsPhotoAndVideoAndPlayerPausesAndReleases() {
        assertEquals(35, Build.VERSION.SDK_INT)
        val originalAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val owner = UUID.randomUUID().toString()
        val title = "系统选择器验证-$owner"
        val clip = File(context.cacheDir, "diary-picker-$owner.mp4")
        val uris = mutableListOf<android.net.Uri>()
        val initialOrientation = compose.activity.requestedOrientation
        var draftId: String? = null
        try {
            // Create exactly two owned assets through the real provider, then publish before launch.
            val image = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values("diary-picker-$owner.png", "image/png", "Pictures/Diary-picker-tests"))).also(uris::add)
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffef9a9a.toInt()) }
            try { resolver.openOutputStream(image)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            assertEquals(1, resolver.update(image, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            createDiaryDeviceVideo(clip, frameCount = 200)
            val video = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values("diary-picker-$owner.mp4", "video/mp4", "Movies/Diary-picker-tests"))).also(uris::add)
            resolver.openOutputStream(video)!!.use { out -> clip.inputStream().use { it.copyTo(out) } }
            assertEquals(1, resolver.update(video, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))

            compose.onNodeWithContentDescription("日记入口").performClick()
            compose.waitUntil(10_000) { !vm().uiState.value.loading }
            node("写日记").performClick()
            compose.waitUntil(10_000) { vm().uiState.value.draft != null && !vm().uiState.value.busy }
            draftId = vm().uiState.value.draft!!.id
            node("标题（可不填）").performTextInput(title)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            node("添加照片 / 视频").performClick()
            awaitNative { it.packageName?.toString() == "com.android.providers.media.module" }
            capturePicker(context, "opened")
            selectTwoNativeMedia()
            capturePicker(context, "selected")
            // Read the actual native button text after two choices; click its accessibility action.
            val add = awaitNative { it.isClickable && it.isEnabled &&
                (it.text?.toString()?.contains("Add", ignoreCase = true) == true || it.text?.toString()?.contains("添加") == true) }
            assertTrue(add.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(20_000) {
                vm().uiState.value.let { it.draft?.media?.size == 2 && !it.busy && it.saveStatus == DiarySaveStatus.SAVED }
            }
            val attachments = vm().uiState.value.draft!!.media
            assertEquals(setOf("image/png", "video/mp4"), attachments.map { it.mimeType }.toSet())
            assertTrue(attachments.all { vm().mediaFile(it)?.isFile == true })

            node("▶ 播放").performClick()
            val first = awaitVideo()
            startActualPlayer(first)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            awaitMain { !first.isPlaying }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            awaitMain { first.isAttachedToWindow }
            assertOnMain { assertFalse(first.isPlaying, "Returning from background must not silently restart video") }
            startActualPlayer(first)
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            awaitMain { !first.isAttachedToWindow && !first.isPlaying }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("关闭").fetchSemanticsNodes().isEmpty() }

            node("▶ 播放").performClick()
            val rotated = awaitVideo()
            startActualPlayer(rotated)
            compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            awaitMain { !rotated.isAttachedToWindow && !rotated.isPlaying }
            compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            assertEquals(2, vm().uiState.value.draft!!.media.size)
            node("完成").performClick()
            compose.waitUntil(10_000) { vm().uiState.value.detail?.title == title && !vm().uiState.value.busy }
            node(title).assertExists()
            capturePicker(context, "diary-detail")
            node("返回").performClick()
            compose.waitUntil(10_000) { vm().uiState.value.detail == null && !vm().uiState.value.busy }
            node(title).assertExists()
            capturePicker(context, "diary-list")
            node("日历").performClick()
            node(java.time.YearMonth.now().toString()).assertExists()
            capturePicker(context, "diary-calendar")
            node(title).performClick()
            compose.waitUntil(10_000) { vm().uiState.value.detail?.title == title }
            Log.i("DiaryPickerDeviceTest", "Native picker selected PNG + MP4; actual VideoView played, paused in background, released on Back and rotation")
        } finally {
            // Close any native picker so subsequent test cleanup acts on its own app window.
            if (automation.rootInActiveWindow?.packageName?.toString() == "com.android.providers.media.module")
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle {
                compose.activity.requestedOrientation = initialOrientation
                val model = vm(); val state = model.uiState.value
                if (state.draft?.id == draftId && draftId != null) { model.askDiscard(state.stamp); model.confirmDiscard(model.uiState.value.stamp) }
                else if (state.detail?.title == title) { model.askDelete(state.stamp); model.confirmDelete(model.uiState.value.stamp) }
            }
            compose.waitUntil(10_000) { vm().uiState.value.let { !it.busy && it.drafts.none { d -> d.id == draftId } && it.entries.none { e -> e.title == title } } }
            uris.forEach { resolver.delete(it, null, null) }
            clip.delete()
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags }
        }
    }

    private fun selectTwoNativeMedia() {
        // Observe current native semantics, never depend on screen coordinates or URI injection.
        // Picker thumbnails expose a selection action and a media-specific content description.
        val chosenBounds = mutableSetOf<String>()
        repeat(2) {
            val candidate = awaitNative { node ->
                val description = node.contentDescription?.toString().orEmpty()
                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }.toShortString()
                node.isClickable && !node.isSelected && !node.isChecked && description.isNotBlank() &&
                    (node.isCheckable || description.contains("photo", true) || description.contains("video", true) ||
                        description.contains("照片") || description.contains("视频")) &&
                    description !in setOf("Photos", "Albums", "照片", "相册") && bounds !in chosenBounds
            }
            chosenBounds += android.graphics.Rect().also { candidate.getBoundsInScreen(it) }.toShortString()
            Log.i("DiaryPickerDeviceTest", "Select observed native item: ${candidate.viewIdResourceName} ${candidate.contentDescription}")
            assertTrue(candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(250)
        }
    }
    private fun awaitNative(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            automation.rootInActiveWindow?.let { root -> allNodes(root).firstOrNull(predicate)?.let { return it } }
            SystemClock.sleep(100)
        }
        val tree = automation.rootInActiveWindow?.let { allNodes(it).joinToString("\n") { n -> describe(n) } }.orEmpty()
        fail("Native accessibility target missing. Observed tree:\n$tree")
    }
    private fun capturePicker(context: Context, phase: String) {
        val prefix = File(context.getExternalFilesDir(null), "diary-picker-$phase")
        val observed = automation.rootInActiveWindow?.let { allNodes(it).joinToString("\n") { n -> describe(n) } }.orEmpty()
        File("${prefix.absolutePath}.txt").writeText(observed)
        Log.i("DiaryPickerDeviceTest", "Observed picker $phase:\n$observed")
        automation.takeScreenshot()?.let { bitmap ->
            try { File("${prefix.absolutePath}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
    private fun describe(node: AccessibilityNodeInfo) = "${node.packageName} ${node.viewIdResourceName} ${node.className} text=${node.text} description=${node.contentDescription} clickable=${node.isClickable} selected=${node.isSelected} checked=${node.isChecked} checkable=${node.isCheckable}"
    private fun allNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) { add(node); repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        visit(root)
    }
    private fun values(name: String, mime: String, path: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, path); put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    private fun node(text: String): SemanticsNodeInteraction {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }
    private fun vm() = ViewModelProvider(compose.activity, compose.activity.diaryFactory)[DiaryViewModel::class.java]
    private fun awaitVideo(): VideoView {
        var found: VideoView? = null
        awaitMain {
            fun search(view: View): VideoView? = when (view) {
                is VideoView -> view
                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { search(view.getChildAt(it)) }
                else -> null
            }
            found = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::search)
            found?.duration?.let { it > 0 } == true
        }
        return requireNotNull(found)
    }
    private fun startActualPlayer(player: VideoView) {
        assertOnMain { player.seekTo(0); player.start() }
        awaitMain { player.isPlaying && player.currentPosition > 0 }
    }
    private fun awaitMain(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for the actual native VideoView lifecycle")
    }
    private fun assertOnMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
