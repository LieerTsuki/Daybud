package com.example.lifemanager.ui.navigation

import com.example.lifemanager.ui.plan.EditorTestWorld

/** Keep the real editor state machine in navigation regressions. */
internal val EditorTestWorld.navigationEditor: PlanNavigationEditor
    get() = PlanNavigationEditorAdapter(model)
