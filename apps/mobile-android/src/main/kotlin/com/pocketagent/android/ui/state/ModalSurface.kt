package com.pocketagent.android.ui.state

sealed class ModalSurface {
    data object None : ModalSurface()
    data object SessionDrawer : ModalSurface()
    data object AdvancedSettings : ModalSurface()
    data object CompletionSettings : ModalSurface()
    data object ModelLibrary : ModalSurface()
    data object PresetCustomization : ModalSurface()
    data object ToolSuggestions : ModalSurface()
    data object Onboarding : ModalSurface()
}
