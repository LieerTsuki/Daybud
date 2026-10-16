package com.example.lifemanager.ui.plan

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Trigger fresh admitted reads on appearance/resume and while visible; never runs in the background. */
@Composable internal fun RefreshPlanOverview(model:Any,refresh:(ZoneId)->Unit) {
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(model,owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) { refresh(ZoneId.systemDefault());delay(60_000) }
        }
    }
}
