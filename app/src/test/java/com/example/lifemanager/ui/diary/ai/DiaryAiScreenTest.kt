package com.example.lifemanager.ui.diary.ai

import android.app.Application
import android.content.Context
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.lifemanager.data.diary.ai.DiaryAiEngine
import com.example.lifemanager.domain.diary.ai.*
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class DiaryAiScreenTest {
    @get:Rule val compose=createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    private fun selectDate(value:String) {
        val millis=LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val locale=ApplicationProvider.getApplicationContext<Context>().resources.configuration.locales[0]
        val description=DatePickerDefaults.dateFormatter().formatDate(millis,locale,true)!!
        compose.onNode(hasText(description,substring=true) and hasClickAction()).performClick()
    }

    @Test fun pickerPreservesCivilDateOutsideUtcAndDisablingDismissesTentativeSelection() {
        val original=TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            val date=mutableStateOf("2026-03-01")
            val enabled=mutableStateOf(true)
            compose.setContent { LifeManagerTheme { AiReviewDateInput("开始日期",date.value,enabled.value) { date.value=it } } }
            compose.onNodeWithContentDescription("选择开始日期").performClick()
            selectDate("2026-03-02")
            compose.onNodeWithText("确定").performClick()
            assertEquals("2026-03-02",date.value)
            compose.onNodeWithContentDescription("选择开始日期").performClick()
            selectDate("2026-03-03")
            compose.runOnIdle { enabled.value=false }
            compose.onNodeWithText("确定").assertDoesNotExist()
            compose.onNodeWithContentDescription("选择开始日期").assertIsNotEnabled()
            compose.runOnIdle { enabled.value=true }
            compose.onNodeWithText("确定").assertDoesNotExist()
            assertEquals("2026-03-02",date.value)
        } finally { TimeZone.setDefault(original) }
    }
    private fun viewModel(): DiaryAiViewModel {
        val source=object:DiaryAiDataSource {
            override suspend fun snapshot(from:LocalDate,through:LocalDate)=AiDiarySnapshot(0,from,through,emptyList())
            override suspend fun isCurrent(snapshot:AiDiarySnapshot)=true
            override suspend fun save(review:AiDiaryReview,snapshot:AiDiarySnapshot)=true
            override fun observeReviews()=flowOf(emptyList<AiDiaryReview>())
            override suspend fun deleteReview(id:String)=Unit
        }
        val store=object:AiStateStore {
            override suspend fun config():AiServiceConfig?=null
            override suspend fun saveConfig(address:String,model:String,key:String)=error("unused")
            override suspend fun consents()=emptyList<AiAutoConsent>()
            override suspend fun setConsent(consent:AiAutoConsent)=Unit
            override suspend fun jobs()=emptyList<AiAutoJob>()
            override suspend fun putJob(job:AiAutoJob)=Unit
            override suspend fun pauseAutomatic()=Unit
        }
        val gateway=object:AiCompletionGateway {
            override suspend fun complete(config:AiServiceConfig,sources:List<AiDiarySource>,partials:List<AiReviewSection>,beforeSend:suspend ()->Unit):List<AiReviewSection> = error("unused")
            override suspend fun probe(config:AiServiceConfig)=Unit
        }
        return DiaryAiViewModel(DiaryAiEngine(source,store,gateway),source,store)
    }

    @Test fun dateButtonsSelectBothRangeEndpointsAndCancelKeepsOriginalValue() {
        val model=viewModel().apply { range("2026-10-12","2026-10-18") }
        compose.setContent { LifeManagerTheme { DiaryAiScreen(model,{},{},{}) } }
        compose.onNodeWithContentDescription("选择开始日期").performScrollTo().performClick()
        selectDate("2026-10-20")
        compose.onNodeWithText("取消").performClick()
        assertEquals("2026-10-12",model.uiState.value.from)
        compose.onNodeWithContentDescription("选择开始日期").performClick()
        selectDate("2026-10-10")
        compose.onNodeWithText("确定").performClick()
        assertEquals("2026-10-10",model.uiState.value.from)
        assertEquals("2026-10-18",model.uiState.value.through)
        compose.onNodeWithContentDescription("选择结束日期").performScrollTo().performClick()
        selectDate("2026-10-19")
        compose.onNodeWithText("确定").performClick()
        assertEquals("2026-10-19",model.uiState.value.through)
        assertEquals("2026-10-10",model.uiState.value.from)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(0)
    }
}
