package com.example.lifemanager.ui.diary.ai

import com.example.lifemanager.domain.diary.ai.AiExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AiOfflineUiStateTest {
    @Test fun firstRenderAlwaysShowsPhoneOfflineEvenBeforeSettingsLoad() {
        assertEquals(AiExecutionMode.LOCAL, DiaryAiUiState().mode)
    }
}
