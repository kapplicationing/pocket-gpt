package com.pocketagent.android.ui

import com.pocketagent.android.ui.state.activeSession
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketagent.android.BuildConfig
import com.pocketagent.android.R
import com.pocketagent.android.runtime.PresetBackingStore
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.resolveAppForegroundRuntimeServices
import com.pocketagent.android.ui.state.ChatGatePrimaryAction
import com.pocketagent.android.ui.state.ChatGateState
import com.pocketagent.android.ui.state.ChatGateStatus
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.resolveChatGateState
import com.pocketagent.android.ui.theme.PocketTheme
import com.pocketagent.android.voice.VoiceActivationController
import com.pocketagent.android.voice.VoiceActivationUiState
import com.pocketagent.core.ModelPreset
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.ModelInteractionRegistry
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.ThinkingSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun PocketAgentApp(
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
    voiceController: VoiceActivationController? = null,
    debugModelLibraryReadyTagEnabled: Boolean = false,
) {
    val bootstrapCompleted by viewModel.bootstrapCompletedFlow.collectAsState()
    val runtime by viewModel.runtimeFlow.collectAsState()
    val activeSurface by viewModel.activeSurfaceFlow.collectAsState()
    val advancedUnlocked by viewModel.advancedUnlockedFlow.collectAsState()
    val modelLoadingState by provisioningViewModel.modelLoadingState.collectAsState()
    val provisioningSnapshot by provisioningViewModel.provisioningSnapshotFlow.collectAsState()
    val downloads by provisioningViewModel.downloadsFlow.collectAsState()
    val context = LocalContext.current
    if (!bootstrapCompleted || provisioningSnapshot == null) {
        ProvisioningBootstrapScreen()
        return
    }
    val chatGateState by remember(provisioningSnapshot, runtime, advancedUnlocked) {
        derivedStateOf {
            val snap = provisioningSnapshot ?: return@derivedStateOf ChatGateState(
                status = ChatGateStatus.BLOCKED_MODEL_MISSING,
                primaryAction = ChatGatePrimaryAction.OPEN_MODEL_SETUP,
            )
            resolveChatGateState(
                runtime = runtime,
                provisioningSnapshot = snap,
                advancedUnlocked = advancedUnlocked,
            )
        }
    }
    val headerUiState by remember(modelLoadingState) {
        derivedStateOf {
            deriveChatHeaderUiState(
                modelLoadingState = modelLoadingState,
            )
        }
    }
    val activeRuntimeModelLabel = headerUiState.activeRuntimeModelLabel
    val activeModelId by remember(modelLoadingState.loadedModel) {
        derivedStateOf {
            modelLoadingState.loadedModel?.modelId
        }
    }
    val canAttachImages by remember(activeModelId) {
        derivedStateOf { canAttachImagesForModel(activeModelId) }
    }
    val hasInstalledModels by remember(provisioningSnapshot) {
        derivedStateOf {
            provisioningSnapshot?.models.orEmpty().any { model -> model.installedVersions.isNotEmpty() }
        }
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val currentActiveSurface by rememberUpdatedState(activeSurface)
    val foregroundRuntimeServices = remember(context) {
        resolveAppForegroundRuntimeServices(context)
    }
    val interactionRegistry = remember(context) {
        ModelInteractionRegistry(
            specProvider = foregroundRuntimeServices.modelSpecProvider,
        )
    }
    val thinkingToggleModelId by remember(modelLoadingState.loadedModel) {
        derivedStateOf {
            modelLoadingState.loadedModel?.modelId
        }
    }
    val showThinkingToggle by remember(thinkingToggleModelId, interactionRegistry) {
        derivedStateOf {
            thinkingToggleModelId?.let { modelId ->
                runCatching {
                    interactionRegistry.interactionProfileForModel(modelId).thinkingSupport == ThinkingSupport.THINK_TAGS
                }.getOrDefault(false)
            } == true
        }
    }
    val canLoadLastUsedModel = headerUiState.canLoadLastUsedModel
    val lastUsedModelLabel = headerUiState.lastUsedModelLabel
    val appViewModel: ChatAppViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val resolvedVoiceController = remember(context, voiceController) {
        voiceController ?: VoiceActivationController(context.applicationContext)
    }
    val voiceState by resolvedVoiceController.observe().collectAsState()
    val sessionDeleteUndoState = rememberSessionDeleteUndoState(
        snackbarHostState = snackbarHostState,
        onCommitDelete = viewModel::deleteSession,
    )
    val isOffline = rememberIsOffline()
    val pendingGetReadyActivation by appViewModel.pendingGetReadyActivation.collectAsState()
    val pendingMeteredWarningVersion by appViewModel.pendingMeteredWarningVersion.collectAsState()
    val pendingRoutingModeSwitch by appViewModel.pendingRoutingModeSwitch.collectAsState()
    val lastDownloadTransitionRefreshKey by appViewModel.lastDownloadTransitionRefreshKey.collectAsState()
    val readinessRefreshSequence by appViewModel.readinessRefreshSequence.collectAsState()
    val defaultGetReadyModelId = remember { resolveDefaultGetReadyModelId(isDebugBuild = BuildConfig.DEBUG) }
    LaunchedEffect(modelLoadingState) {
        viewModel.syncRuntimeModelLoadingState(modelLoadingState)
    }
    val chatAppLaunchers = rememberChatAppLaunchers(
        context = context,
        scope = scope,
        snackbarHostState = snackbarHostState,
        appViewModel = appViewModel,
        viewModel = viewModel,
        provisioningViewModel = provisioningViewModel,
        voiceController = resolvedVoiceController,
        voiceState = voiceState,
    )
    val openModelSheet: () -> Unit = {
        viewModel.showSurface(ModalSurface.ModelLibrary)
    }
    val currentModelLoadingState = rememberUpdatedState(modelLoadingState)
    val modelLibraryActions = remember(
        context,
        viewModel,
        provisioningViewModel,
        appViewModel,
        chatAppLaunchers,
        defaultGetReadyModelId,
        snackbarHostState,
    ) {
        ModelLibraryActions(
            context = context,
            viewModel = viewModel,
            provisioningViewModel = provisioningViewModel,
            appViewModel = appViewModel,
            chatAppLaunchers = chatAppLaunchers,
            defaultGetReadyModelId = defaultGetReadyModelId,
            modelLoadingStateProvider = { currentModelLoadingState.value },
            modelLibraryStateProvider = {
                checkNotNull(provisioningViewModel.uiState.value.toModelLibraryUiState(defaultGetReadyModelId)) {
                    "Model library state is unavailable before provisioning bootstrap completes."
                }
            },
            showBusyModelOperationFeedback = {
                snackbarHostState.showSnackbar(context.getString(R.string.ui_model_operation_already_in_progress))
            },
        )
    }
    val modelRemoveUndoState = rememberModelRemoveUndoState(
        snackbarHostState = snackbarHostState,
        onCommitRemove = { modelId, version ->
            scope.launch {
                modelLibraryActions.removeVersion(modelId, version)
            }
        },
    )
    val loadModelVersionAction: (String, String, Boolean) -> Unit = { modelId, version, closeOnSuccess ->
        scope.launch {
            modelLibraryActions.loadModelVersion(modelId, version, closeOnSuccess)
        }
    }
    val loadLastUsedModelAction: (Boolean) -> Unit = { closeOnSuccess ->
        scope.launch {
            modelLibraryActions.loadLastUsedModel(closeOnSuccess)
        }
    }
    val offloadModelAction: (Boolean) -> Unit = { closeOnSuccess ->
        scope.launch {
            modelLibraryActions.offloadModel(closeOnSuccess)
        }
    }
    val refreshAction: () -> Unit = {
        scope.launch {
            modelLibraryActions.refreshAll()
        }
    }
    val runGetReadyFlow: () -> Unit = {
        scope.launch {
            modelLibraryActions.runGetReadyFlow()
        }
    }
    val onBlockedAction: (ChatGatePrimaryAction) -> Unit = { action ->
        when (action) {
            ChatGatePrimaryAction.GET_READY -> runGetReadyFlow()
            ChatGatePrimaryAction.OPEN_MODEL_SETUP -> openModelSheet()
            ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS -> refreshAction()
            ChatGatePrimaryAction.NONE -> Unit
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            resolvedVoiceController.refresh()
        }
    }
    val queueLoadForRoutingMode: (RoutingMode) -> Unit = queueLoad@ { mode ->
        val snapshot = provisioningSnapshot ?: return@queueLoad
        val targetModelId = ModelCatalog.modelIdForRoutingMode(mode)
        val targetVersion = snapshot.models
            .firstOrNull { model -> model.modelId == targetModelId }
            ?.installedVersions
            ?.firstOrNull { version -> version.isActive }
            ?.version
            ?: snapshot.models
                .firstOrNull { model -> model.modelId == targetModelId }
                ?.installedVersions
                ?.firstOrNull()
                ?.version
        if (
            mode != RoutingMode.AUTO &&
            targetModelId != null &&
            !targetVersion.isNullOrBlank() &&
            modelLoadingState.loadedModel?.modelId != targetModelId
        ) {
            appViewModel.setPendingRoutingModeSwitch(targetModelId to targetVersion)
        }
    }
    val handleRoutingModeSelected: (RoutingMode) -> Unit = { mode ->
        viewModel.setRoutingMode(mode)
        queueLoadForRoutingMode(mode)
    }
    val handlePresetSelected: (ModelPreset) -> Unit = { preset ->
        viewModel.setModelPreset(preset)
        queueLoadForRoutingMode(viewModel.presetBackingStore.routingModeForPreset(preset))
    }
    val onPresetBackingChanged: (ModelPreset, String) -> Unit = { preset, modelId ->
        val matchedBefore = viewModel.presetBackingStore.presetMatchingRoutingMode(runtime.routingMode)
        viewModel.setCustomPresetBacking(preset, modelId)
        if (matchedBefore == preset) {
            handleRoutingModeSelected(viewModel.presetBackingStore.routingModeForPreset(preset))
        }
    }
    val onResetPresetMappings: () -> Unit = {
        val matched = viewModel.presetBackingStore.presetMatchingRoutingMode(runtime.routingMode)
        viewModel.resetPresetMappingsToDefaults()
        if (matched != null && matched != ModelPreset.AUTO) {
            handleRoutingModeSelected(viewModel.presetBackingStore.routingModeForPreset(matched))
        }
    }

    BackHandler(
        enabled = activeSurface != ModalSurface.None || drawerState.currentValue == DrawerValue.Open,
    ) {
        when (activeSurface) {
            is ModalSurface.Onboarding -> viewModel.skipOnboarding()
            is ModalSurface.SessionDrawer -> viewModel.dismissSurface()
            ModalSurface.None -> scope.launch { drawerState.close() }
            else -> viewModel.dismissSurface()
        }
    }

    DownloadTransitionHandler(
        downloads = downloads,
        pendingGetReadyActivation = pendingGetReadyActivation,
        loadedModelId = modelLoadingState.loadedModel?.modelId,
        lastDownloadTransitionRefreshKey = lastDownloadTransitionRefreshKey,
        readinessRefreshSequence = readinessRefreshSequence,
        onRefreshSnapshot = provisioningViewModel::refreshSnapshot,
        onSetStatusMessage = provisioningViewModel::setStatusMessage,
        onActivateVersion = modelLibraryActions::activateVersion,
        onLoadModel = { modelId, version ->
            modelLibraryActions.loadModelVersion(modelId, version, closeOnSuccess = false)
        },
        onShowBusyModelOperationFeedback = {
            snackbarHostState.showSnackbar(context.getString(R.string.ui_model_operation_already_in_progress))
        },
        onClearPendingGetReadyActivation = { appViewModel.setPendingGetReadyActivation(null) },
        onIncrementReadinessRefreshSequence = appViewModel::incrementReadinessRefreshSequence,
        onRefreshRuntimeReadiness = viewModel::refreshRuntimeReadiness,
        onSetLastDownloadTransitionRefreshKey = appViewModel::setLastDownloadTransitionRefreshKey,
        onOpenModelSheet = openModelSheet,
    )

    LaunchedEffect(activeSurface) {
        if (activeSurface is ModalSurface.SessionDrawer) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue }
            .map { it == DrawerValue.Closed }
            .distinctUntilChanged()
            .filter { it }
            .collectLatest {
                if (currentActiveSurface is ModalSurface.SessionDrawer) {
                    viewModel.dismissSurface()
                }
            }
    }

    ModalNavigationDrawer(
        modifier = Modifier.semantics {
            testTagsAsResourceId = true
        },
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxHeight()) {
                SessionDrawerHost(
                    viewModel = viewModel,
                    onCreateSession = {
                        viewModel.createSession()
                        viewModel.dismissSurface()
                    },
                    onSwitchSession = { id ->
                        viewModel.switchSession(id)
                        viewModel.dismissSurface()
                    },
                    onDeleteSession = sessionDeleteUndoState.requestDelete,
                    hiddenSessionIds = sessionDeleteUndoState.hiddenSessionIds,
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                PocketAgentTopBar(
                    activeRuntimeModelLabel = activeRuntimeModelLabel,
                    hasInstalledModels = hasInstalledModels,
                    onOpenSessionDrawer = { viewModel.showSurface(ModalSurface.SessionDrawer) },
                    onModelPresetSelected = handlePresetSelected,
                    onOpenModelLibrary = openModelSheet,
                    onOpenAdvancedSettings = { viewModel.showSurface(ModalSurface.AdvancedSettings) },
                )
            },
            bottomBar = {
                ChatComposerDock(
                    viewModel = viewModel,
                    chatGateState = chatGateState,
                    canAttachImages = canAttachImages,
                    showThinkingToggle = showThinkingToggle,
                    onAttachImage = chatAppLaunchers.launchImageAttachmentPicker,
                    onBlockedAction = onBlockedAction,
                )
            },
        ) { innerPadding ->
            ChatScreenHost(
                viewModel = viewModel,
                runtime = runtime,
                modelLoadingState = modelLoadingState,
                onSuggestedPrompt = viewModel::prefillComposer,
                onOpenModels = openModelSheet,
                canLoadLastUsedModel = canLoadLastUsedModel,
                lastUsedModelLabel = lastUsedModelLabel,
                onLoadLastUsedModel = { loadLastUsedModelAction(false) },
                activeRuntimeModelLabel = activeRuntimeModelLabel,
                onRefresh = refreshAction,
                isOffline = isOffline,
                onOpenToolDialog = { viewModel.showSurface(ModalSurface.ToolSuggestions) },
                onEditMessage = viewModel::editMessage,
                onRegenerateMessage = viewModel::regenerateResponse,
                onCopiedToClipboard = {
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_copied_to_clipboard)) }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    ModelLibrarySheetHost(
        activeSurface = activeSurface,
        provisioningViewModel = provisioningViewModel,
        defaultGetReadyModelId = defaultGetReadyModelId,
        modelLoadingState = modelLoadingState,
        routingMode = runtime.routingMode,
        presetBackingStore = viewModel.presetBackingStore,
        modelRemoveUndoState = modelRemoveUndoState,
        actions = modelLibraryActions,
        debugModelLibraryReadyTagEnabled = debugModelLibraryReadyTagEnabled,
    )

    ModalOrchestratorHost(
        viewModel = viewModel,
        activeSurface = activeSurface,
        voiceState = voiceState,
        provisioningViewModel = provisioningViewModel,
        defaultGetReadyModelId = defaultGetReadyModelId,
        presetBackingStore = viewModel.presetBackingStore,
        pendingRoutingModeSwitch = pendingRoutingModeSwitch,
        pendingMeteredWarningVersion = pendingMeteredWarningVersion,
        onDismissSurface = viewModel::dismissSurface,
        onUseToolPrompt = viewModel::prefillComposer,
        onDefaultThinkingEnabledChanged = viewModel::setDefaultThinkingEnabled,
        onModelPresetSelected = handlePresetSelected,
        onOpenPresetCustomization = { viewModel.showSurface(ModalSurface.PresetCustomization) },
        onPresetBackingChanged = onPresetBackingChanged,
        onResetPresetMappings = onResetPresetMappings,
        onPerformanceProfileSelected = viewModel::setPerformanceProfile,
        onKeepAlivePreferenceSelected = viewModel::setKeepAlivePreference,
        onVoiceActivationChanged = chatAppLaunchers.toggleVoiceActivation,
        onRequestAssistantRole = chatAppLaunchers.requestAssistantRole,
        onOpenBatteryOptimizationSettings = chatAppLaunchers.openBatteryOptimizationSettings,
        onOpenAppSettings = chatAppLaunchers.openAppSettings,
        onWifiOnlyDownloadsChanged = { enabled ->
            scope.launch { provisioningViewModel.setDownloadWifiOnlyEnabledAsync(enabled) }
        },
        onGpuAccelerationEnabledChanged = viewModel::setGpuAccelerationEnabled,
        onExportDiagnostics = viewModel::exportDiagnostics,
        onCompletionSettingsChanged = viewModel::updateSessionCompletionSettings,
        onDismissRoutingModeSwitch = { appViewModel.setPendingRoutingModeSwitch(null) },
        onConfirmRoutingModeSwitch = { modelId, version ->
            appViewModel.setPendingRoutingModeSwitch(null)
            loadModelVersionAction(modelId, version, false)
        },
        onDismissMeteredDownloadWarning = { appViewModel.setPendingMeteredWarningVersion(null) },
        onConfirmMeteredDownloadWarning = { version ->
            scope.launch {
                provisioningViewModel.acknowledgeLargeDownloadCellularWarningAsync()
                appViewModel.setPendingMeteredWarningVersion(null)
                chatAppLaunchers.launchDownloadFlow(version)
            }
        },
        onOnboardingPageChanged = viewModel::setOnboardingPage,
        onNextOnboardingPage = viewModel::nextOnboardingPage,
        onSkipOnboarding = viewModel::skipOnboarding,
        onFinishOnboarding = viewModel::completeOnboarding,
        onStartOnboardingDownload = runGetReadyFlow,
    )
}

@Composable
private fun ChatComposerDock(
    viewModel: ChatViewModel,
    chatGateState: ChatGateState,
    canAttachImages: Boolean,
    showThinkingToggle: Boolean,
    onAttachImage: () -> Unit,
    onBlockedAction: (ChatGatePrimaryAction) -> Unit,
) {
    val composer by viewModel.composerFlow.collectAsState()
    val activeSessionId by viewModel.activeSessionIdFlow.collectAsState()
    val thinkingEnabled by viewModel.currentThinkingEnabledFlow.collectAsState()
    ComposerBar(
        text = composer.text,
        isSending = composer.isSending,
        isCancelling = composer.isCancelling,
        chatGateState = chatGateState,
        editingMessageId = composer.editingMessageId,
        attachedImages = composer.attachedImages,
        activeSessionId = activeSessionId,
        onTextChanged = viewModel::onComposerChanged,
        onSend = viewModel::sendMessage,
        onCancelSend = viewModel::cancelActiveSend,
        onSubmitEdit = viewModel::submitEdit,
        onCancelEdit = viewModel::cancelEdit,
        onAttachImage = onAttachImage,
        canAttachImages = canAttachImages,
        onRemoveImage = viewModel::removeAttachedImage,
        onOpenToolDialog = { viewModel.showSurface(ModalSurface.ToolSuggestions) },
        showThinkingToggle = showThinkingToggle,
        thinkingEnabled = thinkingEnabled,
        onToggleThinking = viewModel::toggleSessionThinking,
        onOpenCompletionSettings = { viewModel.showSurface(ModalSurface.CompletionSettings) },
        onBlockedAction = onBlockedAction,
    )
}

@Composable
private fun SessionDrawerHost(
    viewModel: ChatViewModel,
    onCreateSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (ChatSessionUiModel) -> Unit,
    hiddenSessionIds: Set<String> = emptySet(),
) {
    val sessions by viewModel.sessionsFlow.collectAsState()
    val activeSessionId by viewModel.activeSessionIdFlow.collectAsState()
    SessionDrawer(
        sessions = sessions,
        activeSessionId = activeSessionId,
        onCreateSession = onCreateSession,
        onSwitchSession = onSwitchSession,
        onDeleteSession = onDeleteSession,
        hiddenSessionIds = hiddenSessionIds,
    )
}

@Composable
private fun ChatScreenHost(
    viewModel: ChatViewModel,
    runtime: RuntimeUiState,
    modelLoadingState: ModelLoadingState,
    onSuggestedPrompt: (String) -> Unit,
    onOpenModels: () -> Unit,
    canLoadLastUsedModel: Boolean,
    lastUsedModelLabel: String?,
    onLoadLastUsedModel: () -> Unit,
    activeRuntimeModelLabel: String?,
    onRefresh: () -> Unit,
    isOffline: Boolean,
    onOpenToolDialog: () -> Unit,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCopiedToClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeSession by viewModel.activeSessionFlow.collectAsState()
    val streaming by viewModel.streamingFlow.collectAsState()
    ChatScreenBody(
        runtime = runtime,
        activeSession = activeSession,
        streaming = streaming,
        modelLoadingState = modelLoadingState,
        onSuggestedPrompt = onSuggestedPrompt,
        onOpenModels = onOpenModels,
        canLoadLastUsedModel = canLoadLastUsedModel,
        lastUsedModelLabel = lastUsedModelLabel,
        onLoadLastUsedModel = onLoadLastUsedModel,
        activeRuntimeModelLabel = activeRuntimeModelLabel,
        onRefresh = onRefresh,
        isOffline = isOffline,
        onOpenToolDialog = onOpenToolDialog,
        onEditMessage = onEditMessage,
        onRegenerateMessage = onRegenerateMessage,
        onCopiedToClipboard = onCopiedToClipboard,
        modifier = modifier,
    )
}

@Composable
private fun ModalOrchestratorHost(
    viewModel: ChatViewModel,
    activeSurface: ModalSurface,
    voiceState: VoiceActivationUiState,
    provisioningViewModel: ModelProvisioningViewModel,
    defaultGetReadyModelId: String?,
    presetBackingStore: PresetBackingStore,
    pendingRoutingModeSwitch: Pair<String, String>?,
    pendingMeteredWarningVersion: ModelDistributionVersion?,
    onDismissSurface: () -> Unit,
    onUseToolPrompt: (String) -> Unit,
    onDefaultThinkingEnabledChanged: (Boolean) -> Unit,
    onModelPresetSelected: (ModelPreset) -> Unit,
    onOpenPresetCustomization: () -> Unit,
    onPresetBackingChanged: (ModelPreset, String) -> Unit,
    onResetPresetMappings: () -> Unit,
    onPerformanceProfileSelected: (RuntimePerformanceProfile) -> Unit,
    onKeepAlivePreferenceSelected: (RuntimeKeepAlivePreference) -> Unit,
    onVoiceActivationChanged: (Boolean) -> Unit,
    onRequestAssistantRole: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onWifiOnlyDownloadsChanged: (Boolean) -> Unit,
    onGpuAccelerationEnabledChanged: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
    onCompletionSettingsChanged: (CompletionSettings) -> Unit,
    onDismissRoutingModeSwitch: () -> Unit,
    onConfirmRoutingModeSwitch: (String, String) -> Unit,
    onDismissMeteredDownloadWarning: () -> Unit,
    onConfirmMeteredDownloadWarning: (ModelDistributionVersion) -> Unit,
    onOnboardingPageChanged: (Int) -> Unit,
    onNextOnboardingPage: () -> Unit,
    onSkipOnboarding: () -> Unit,
    onFinishOnboarding: () -> Unit,
    onStartOnboardingDownload: () -> Unit,
) {
    if (
        activeSurface == ModalSurface.None &&
        pendingRoutingModeSwitch == null &&
        pendingMeteredWarningVersion == null
    ) {
        return
    }
    val state by viewModel.uiState.collectAsState()
    val provisioningState by provisioningViewModel.uiState.collectAsState()
    val completionSettings by viewModel.currentCompletionSettingsFlow.collectAsState()
    val modelLibraryState = remember(provisioningState, defaultGetReadyModelId) {
        provisioningState.toModelLibraryUiState(defaultGetReadyModelId)
    } ?: return
    ModalOrchestrator(
        state = state,
        voiceState = voiceState,
        provisioningState = provisioningState,
        modelLibraryState = modelLibraryState,
        presetBackingStore = presetBackingStore,
        pendingRoutingModeSwitch = pendingRoutingModeSwitch,
        pendingMeteredWarningVersion = pendingMeteredWarningVersion,
        downloads = provisioningState.downloads,
        onDismissSurface = onDismissSurface,
        onUseToolPrompt = onUseToolPrompt,
        onDefaultThinkingEnabledChanged = onDefaultThinkingEnabledChanged,
        onModelPresetSelected = onModelPresetSelected,
        onOpenPresetCustomization = onOpenPresetCustomization,
        onPresetBackingChanged = onPresetBackingChanged,
        onResetPresetMappings = onResetPresetMappings,
        onPerformanceProfileSelected = onPerformanceProfileSelected,
        onKeepAlivePreferenceSelected = onKeepAlivePreferenceSelected,
        onVoiceActivationChanged = onVoiceActivationChanged,
        onRequestAssistantRole = onRequestAssistantRole,
        onOpenBatteryOptimizationSettings = onOpenBatteryOptimizationSettings,
        onOpenAppSettings = onOpenAppSettings,
        onWifiOnlyDownloadsChanged = onWifiOnlyDownloadsChanged,
        onGpuAccelerationEnabledChanged = onGpuAccelerationEnabledChanged,
        onExportDiagnostics = onExportDiagnostics,
        completionSettings = completionSettings,
        onCompletionSettingsChanged = onCompletionSettingsChanged,
        onDismissRoutingModeSwitch = onDismissRoutingModeSwitch,
        onConfirmRoutingModeSwitch = onConfirmRoutingModeSwitch,
        onDismissMeteredDownloadWarning = onDismissMeteredDownloadWarning,
        onConfirmMeteredDownloadWarning = onConfirmMeteredDownloadWarning,
        onOnboardingPageChanged = onOnboardingPageChanged,
        onNextOnboardingPage = onNextOnboardingPage,
        onSkipOnboarding = onSkipOnboarding,
        onFinishOnboarding = onFinishOnboarding,
        onStartOnboardingDownload = onStartOnboardingDownload,
    )
}

internal fun canAttachImagesForModel(modelId: String?): Boolean {
    return modelId?.let(ModelCatalog::isVisionCapable) == true
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ProvisioningBootstrapScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag("provisioning_bootstrap_loading"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PocketTheme.spacing.md),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(id = R.string.ui_provisioning_bootstrap_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(id = R.string.ui_provisioning_bootstrap_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
