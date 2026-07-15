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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.pocketagent.android.ui.components.AppBottomSheet
import com.pocketagent.android.ui.components.SettingsDestination
import com.pocketagent.android.ui.components.ConfirmDialog
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
import com.pocketagent.android.voice.VoiceDictationController
import com.pocketagent.android.voice.VoiceDictationIssue
import com.pocketagent.android.voice.VoicePlaybackController
import com.pocketagent.android.voice.VoicePlaybackIssue
import com.pocketagent.android.voice.VoicePlaybackPhase
import com.pocketagent.android.voice.appendDictationToDraft
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
@Suppress("CyclomaticComplexMethod", "LongMethod", "MaxLineLength", "ComplexCondition")
@Composable
fun PocketAgentApp(
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
    voiceController: VoiceActivationController? = null,
    debugModelLibraryReadyTagEnabled: Boolean = false,
    debugModelLibraryStatus: String? = null,
) {
    val bootstrapCompleted by viewModel.bootstrapCompletedFlow.collectAsState()
    val runtime by viewModel.runtimeFlow.collectAsState()
    val activeSurface by viewModel.activeSurfaceFlow.collectAsState()
    val advancedUnlocked by viewModel.advancedUnlockedFlow.collectAsState()
    val modelLoadingState by provisioningViewModel.modelLoadingState.collectAsState()
    val provisioningSnapshot by provisioningViewModel.provisioningSnapshotFlow.collectAsState()
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
    val voiceChatActions = rememberVoiceChatActions(
        context = context,
        alwaysOnListeningEnabled = voiceState.settings.enabled,
        activeSessionId = { viewModel.activeSessionIdFlow.value },
        onTranscriptReady = { transcript ->
            viewModel.onComposerChanged(
                appendDictationToDraft(viewModel.composerFlow.value.text, transcript),
            )
        },
    )
    val sessionDeleteUndoState = rememberSessionDeleteUndoState(
        snackbarHostState = snackbarHostState,
        onCommitDelete = viewModel::deleteSession,
    )
    val isOffline = rememberIsOffline()
    val pendingGetReadyActivation by appViewModel.pendingGetReadyActivation.collectAsState()
    val getReadySetupFailure by appViewModel.getReadySetupFailure.collectAsState()
    val getReadySetupRequestInFlight by appViewModel.getReadySetupRequestInFlight.collectAsState()
    val modelImportRequest by appViewModel.modelImportRequest.collectAsState()
    val modelImportOperation by provisioningViewModel.modelImportOperation.collectAsState()
    val pendingMeteredWarningVersion by appViewModel.pendingMeteredWarningVersion.collectAsState()
    val pendingRoutingModeSwitch by appViewModel.pendingRoutingModeSwitch.collectAsState()
    val lastDownloadTransitionRefreshKey by appViewModel.lastDownloadTransitionRefreshKey.collectAsState()
    val readinessRefreshSequence by appViewModel.readinessRefreshSequence.collectAsState()
    val defaultGetReadyModelId = remember { resolveDefaultGetReadyModelId(isDebugBuild = BuildConfig.DEBUG) }
    LaunchedEffect(modelLoadingState) {
        viewModel.syncRuntimeModelLoadingState(modelLoadingState)
    }
    LaunchedEffect(modelImportOperation) {
        when (val operation = modelImportOperation) {
            is ModelImportOperationState.Succeeded -> {
                val result = operation.result
                val statusMessage = if (result.isActive) {
                    context.getString(
                        R.string.ui_model_import_success_active,
                        result.modelId,
                        result.version,
                    )
                } else {
                    context.getString(
                        R.string.ui_model_import_success_inactive,
                        result.modelId,
                        result.version,
                    )
                }
                viewModel.refreshRuntimeReadiness(statusDetailOverride = statusMessage)
                provisioningViewModel.setStatusMessage(statusMessage)
                provisioningViewModel.acknowledgeModelImportTerminal(operation.operationId)
            }

            is ModelImportOperationState.Failed -> {
                provisioningViewModel.setStatusMessage(
                    context.getString(R.string.ui_model_import_failure, operation.userMessage),
                )
                provisioningViewModel.acknowledgeModelImportTerminal(operation.operationId)
            }

            is ModelImportOperationState.Cancelled -> {
                provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_import_cancelled))
                provisioningViewModel.acknowledgeModelImportTerminal(operation.operationId)
            }

            ModelImportOperationState.Idle,
            is ModelImportOperationState.Running,
            -> Unit
        }
    }
    val chatAppLaunchers = rememberChatAppLaunchers(
        context = context,
        scope = scope,
        snackbarHostState = snackbarHostState,
        appViewModel = appViewModel,
        viewModel = viewModel,
        provisioningViewModel = provisioningViewModel,
        voiceController = resolvedVoiceController,
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
    val refreshAction: () -> Unit = {
        scope.launch {
            modelLibraryActions.refreshAll()
        }
    }
    val runGetReadyFlow: (Boolean) -> Unit = { openModelLibraryOnDownload ->
        if (appViewModel.tryBeginGetReadySetupRequest()) {
            scope.launch {
                try {
                    modelLibraryActions.runGetReadyFlow(openModelLibraryOnDownload)
                } finally {
                    appViewModel.finishGetReadySetupRequest()
                }
            }
        }
    }
    val startOnboardingDownload: () -> Unit = { runGetReadyFlow(false) }
    val onBlockedAction: (ChatGatePrimaryAction) -> Unit = { action ->
        when (action) {
            ChatGatePrimaryAction.GET_READY -> runGetReadyFlow(true)
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
        downloadsFlow = provisioningViewModel.downloadsFlow,
        pendingGetReadyActivation = pendingGetReadyActivation,
        pendingGetReadyTargetIsInstalled = pendingGetReadyActivation?.let { (modelId, version) ->
            provisioningSnapshot?.models
                ?.firstOrNull { model -> model.modelId == modelId }
                ?.installedVersions
                ?.any { installedVersion -> installedVersion.version == version }
                ?: false
        } == true,
        pendingMeteredWarningTarget = pendingMeteredWarningVersion?.let { version ->
            version.modelId to version.version
        },
        setupRequestInFlight = getReadySetupRequestInFlight,
        setupFailureMessage = getReadySetupFailure,
        loadedModel = modelLoadingState.loadedModel,
        activeModelLoadRequest = (modelLoadingState as? ModelLoadingState.Loading)?.requestedModel,
        sendReady = chatGateState.isReady,
        lastDownloadTransitionRefreshKey = lastDownloadTransitionRefreshKey,
        readinessRefreshSequence = readinessRefreshSequence,
        onRefreshSnapshot = provisioningViewModel::refreshSnapshot,
        onSetStatusMessage = provisioningViewModel::setStatusMessage,
        onActivateVersion = modelLibraryActions::activateVersion,
        onLoadModel = { modelId, version ->
            modelLibraryActions.loadModelVersion(
                modelId = modelId,
                version = version,
                closeOnSuccess = false,
                userInitiated = false,
            )
        },
        onShowBusyModelOperationFeedback = {
            snackbarHostState.showSnackbar(context.getString(R.string.ui_model_operation_already_in_progress))
        },
        readPendingGetReadyActivation = { appViewModel.pendingGetReadyActivation.value },
        readLoadedModel = { provisioningViewModel.modelLoadingState.value.loadedModel },
        readActiveModelLoadRequest = {
            (provisioningViewModel.modelLoadingState.value as? ModelLoadingState.Loading)?.requestedModel
        },
        readPendingMeteredWarningTarget = {
            appViewModel.pendingMeteredWarningVersion.value?.let { version ->
                version.modelId to version.version
            }
        },
        onTryBeginGetReadySetupRequest = appViewModel::tryBeginGetReadySetupRequest,
        onFinishGetReadySetupRequest = appViewModel::finishGetReadySetupRequest,
        onClearPendingGetReadyActivation = { appViewModel.setPendingGetReadyActivation(null) },
        onSetGetReadySetupFailure = appViewModel::setGetReadySetupFailure,
        onIncrementReadinessRefreshSequence = appViewModel::incrementReadinessRefreshSequence,
        onRefreshRuntimeReadiness = viewModel::refreshRuntimeReadiness,
        onSetLastDownloadTransitionRefreshKey = appViewModel::setLastDownloadTransitionRefreshKey,
        onOpenModelSheet = openModelSheet,
        keepPendingGetReadyFailure = activeSurface is ModalSurface.Onboarding,
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

    val settingsDestinationActive = activeSurface.isSettingsDestination()
    val retainedShellModifier = Modifier
        .fillMaxSize()
        .focusProperties { canFocus = !settingsDestinationActive }
        .drawWithContent {
            if (!settingsDestinationActive) {
                drawContent()
            }
        }
        .then(
            if (settingsDestinationActive) {
                Modifier
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .forEach { change -> change.consume() }
                            }
                        }
                    }
            } else {
                Modifier
            },
        )
    Box(modifier = retainedShellModifier) {
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
                        consumeImeInsets = activeSurface == ModalSurface.None,
                        autoFocusEnabled = activeSurface == ModalSurface.None &&
                            chatGateState.status == ChatGateStatus.READY,
                        onAttachImage = chatAppLaunchers.launchImageAttachmentPicker,
                        onBlockedAction = onBlockedAction,
                        dictationController = voiceChatActions.dictationController,
                        onToggleDictation = voiceChatActions::toggleDictation,
                        onDictationIssue = { issue ->
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(dictationIssueMessage(issue)))
                            }
                        },
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
                    playbackController = voiceChatActions.playbackController,
                    onReadAloud = voiceChatActions::togglePlayback,
                    onPlaybackIssue = { issue ->
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(playbackIssueMessage(issue)))
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
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
        modelImportRequestActive = modelImportRequest != null ||
            modelImportOperation !is ModelImportOperationState.Idle,
        actions = modelLibraryActions,
        debugModelLibraryReadyTagEnabled = debugModelLibraryReadyTagEnabled,
        debugModelLibraryStatus = debugModelLibraryStatus,
    )

    ModalOrchestratorHost(
        viewModel = viewModel,
        activeSurface = activeSurface,
        runtime = runtime,
        voiceState = voiceState,
        provisioningViewModel = provisioningViewModel,
        defaultGetReadyModelId = defaultGetReadyModelId,
        pendingGetReadyActivation = pendingGetReadyActivation,
        getReadySetupFailure = getReadySetupFailure,
        getReadySetupRequestInFlight = getReadySetupRequestInFlight,
        modelLoadingState = modelLoadingState,
        onboardingSendReady = chatGateState.isReady,
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
        onVoiceActivationChanged = { enabled ->
            if (enabled) {
                voiceChatActions.dictationController.cancel()
            }
            chatAppLaunchers.toggleVoiceActivation(enabled)
        },
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
        onDismissMeteredDownloadWarning = {
            val dismissedVersion = pendingMeteredWarningVersion
            if (
                dismissedVersion != null &&
                pendingGetReadyActivation == (dismissedVersion.modelId to dismissedVersion.version)
            ) {
                appViewModel.setPendingGetReadyActivation(null)
                appViewModel.setGetReadySetupFailure(null)
            }
            appViewModel.setPendingMeteredWarningVersion(null)
        },
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
        onStartOnboardingDownload = startOnboardingDownload,
        onChooseAnotherOnboardingModel = {
            appViewModel.setPendingGetReadyActivation(null)
            appViewModel.setGetReadySetupFailure(null)
            viewModel.completeOnboarding()
            viewModel.showSurface(ModalSurface.ModelLibrary)
        },
    )
}

@Composable
private fun ChatComposerDock(
    viewModel: ChatViewModel,
    chatGateState: ChatGateState,
    canAttachImages: Boolean,
    showThinkingToggle: Boolean,
    consumeImeInsets: Boolean,
    autoFocusEnabled: Boolean,
    onAttachImage: () -> Unit,
    onBlockedAction: (ChatGatePrimaryAction) -> Unit,
    dictationController: VoiceDictationController,
    onToggleDictation: () -> Unit,
    onDictationIssue: (VoiceDictationIssue) -> Unit,
) {
    val composer by viewModel.composerFlow.collectAsState()
    val activeSessionId by viewModel.activeSessionIdFlow.collectAsState()
    val thinkingEnabled by viewModel.currentThinkingEnabledFlow.collectAsState()
    val dictationState by dictationController.observe().collectAsState()
    LaunchedEffect(dictationState.errorEventId) {
        dictationState.issue?.let(onDictationIssue)
    }
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
        consumeImeInsets = consumeImeInsets,
        autoFocusEnabled = autoFocusEnabled,
        dictationState = dictationState,
        onToggleDictation = onToggleDictation,
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
@Suppress("LongParameterList")
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
    playbackController: VoicePlaybackController,
    onReadAloud: (String, String) -> Unit,
    onPlaybackIssue: (VoicePlaybackIssue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeSession by viewModel.activeSessionFlow.collectAsState()
    val streaming by viewModel.streamingFlow.collectAsState()
    val playbackState by playbackController.observe().collectAsState()
    LaunchedEffect(playbackState.errorEventId) {
        playbackState.issue?.let(onPlaybackIssue)
    }
    LaunchedEffect(activeSession?.id) {
        playbackController.stop()
    }
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
        speakingMessageId = playbackState.messageId.takeIf {
            playbackState.phase == VoicePlaybackPhase.SPEAKING
        },
        onReadAloud = onReadAloud,
        modifier = modifier,
    )
}

@Composable
@Suppress("LongParameterList")
private fun ModalOrchestratorHost(
    viewModel: ChatViewModel,
    activeSurface: ModalSurface,
    runtime: RuntimeUiState,
    voiceState: VoiceActivationUiState,
    provisioningViewModel: ModelProvisioningViewModel,
    defaultGetReadyModelId: String?,
    pendingGetReadyActivation: Pair<String, String>?,
    getReadySetupFailure: String?,
    getReadySetupRequestInFlight: Boolean,
    modelLoadingState: ModelLoadingState,
    onboardingSendReady: Boolean,
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
    onChooseAnotherOnboardingModel: () -> Unit,
) {
    if (
        activeSurface == ModalSurface.None &&
        pendingRoutingModeSwitch == null &&
        pendingMeteredWarningVersion == null
    ) {
        return
    }

    if (activeSurface is ModalSurface.ToolSuggestions) {
        ToolDialog(
            onDismiss = onDismissSurface,
            onUsePrompt = onUseToolPrompt,
        )
    }

    AdvancedSettingsModalHost(
        activeSurface = activeSurface,
        viewModel = viewModel,
        provisioningViewModel = provisioningViewModel,
        runtime = runtime,
        voiceState = voiceState,
        presetBackingStore = presetBackingStore,
        onDismissSurface = onDismissSurface,
        onDefaultThinkingEnabledChanged = onDefaultThinkingEnabledChanged,
        onModelPresetSelected = onModelPresetSelected,
        onOpenPresetCustomization = onOpenPresetCustomization,
        onPerformanceProfileSelected = onPerformanceProfileSelected,
        onKeepAlivePreferenceSelected = onKeepAlivePreferenceSelected,
        onVoiceActivationChanged = onVoiceActivationChanged,
        onRequestAssistantRole = onRequestAssistantRole,
        onOpenBatteryOptimizationSettings = onOpenBatteryOptimizationSettings,
        onOpenAppSettings = onOpenAppSettings,
        onWifiOnlyDownloadsChanged = onWifiOnlyDownloadsChanged,
        onGpuAccelerationEnabledChanged = onGpuAccelerationEnabledChanged,
        onExportDiagnostics = onExportDiagnostics,
    )

    PresetCustomizationModalHost(
        activeSurface = activeSurface,
        provisioningViewModel = provisioningViewModel,
        defaultGetReadyModelId = defaultGetReadyModelId,
        presetBackingStore = presetBackingStore,
        onDismissSurface = onDismissSurface,
        onPresetBackingChanged = onPresetBackingChanged,
        onResetPresetMappings = onResetPresetMappings,
    )

    CompletionSettingsModalHost(
        activeSurface = activeSurface,
        viewModel = viewModel,
        onDismissSurface = onDismissSurface,
        onCompletionSettingsChanged = onCompletionSettingsChanged,
    )

    RoutingModeSwitchDialog(
        pending = pendingRoutingModeSwitch,
        onDismiss = onDismissRoutingModeSwitch,
        onConfirm = onConfirmRoutingModeSwitch,
    )

    MeteredDownloadWarningDialog(
        pending = pendingMeteredWarningVersion,
        onDismiss = onDismissMeteredDownloadWarning,
        onConfirm = onConfirmMeteredDownloadWarning,
    )

    OnboardingModalHost(
        activeSurface = activeSurface,
        viewModel = viewModel,
        provisioningViewModel = provisioningViewModel,
        defaultGetReadyModelId = defaultGetReadyModelId,
        pendingGetReadyActivation = pendingGetReadyActivation,
        setupFailureMessage = getReadySetupFailure,
        setupRequestInFlight = getReadySetupRequestInFlight,
        modelLoadingState = modelLoadingState,
        sendReady = onboardingSendReady,
        onOnboardingPageChanged = onOnboardingPageChanged,
        onNextOnboardingPage = onNextOnboardingPage,
        onSkipOnboarding = onSkipOnboarding,
        onFinishOnboarding = onFinishOnboarding,
        onStartOnboardingDownload = onStartOnboardingDownload,
        onChooseAnotherModel = onChooseAnotherOnboardingModel,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
private fun AdvancedSettingsModalHost(
    activeSurface: ModalSurface,
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
    runtime: RuntimeUiState,
    voiceState: VoiceActivationUiState,
    presetBackingStore: PresetBackingStore,
    onDismissSurface: () -> Unit,
    onDefaultThinkingEnabledChanged: (Boolean) -> Unit,
    onModelPresetSelected: (ModelPreset) -> Unit,
    onOpenPresetCustomization: () -> Unit,
    onPerformanceProfileSelected: (RuntimePerformanceProfile) -> Unit,
    onKeepAlivePreferenceSelected: (RuntimeKeepAlivePreference) -> Unit,
    onVoiceActivationChanged: (Boolean) -> Unit,
    onRequestAssistantRole: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onWifiOnlyDownloadsChanged: (Boolean) -> Unit,
    onGpuAccelerationEnabledChanged: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    if (activeSurface !is ModalSurface.AdvancedSettings) {
        return
    }

    val defaultThinkingEnabled by viewModel.defaultThinkingEnabledFlow.collectAsState()
    val downloadPreferences by provisioningViewModel.downloadPreferencesFlow.collectAsState()
    SettingsDestination(
        title = stringResource(id = R.string.ui_advanced_controls_title),
        onClose = onDismissSurface,
        modifier = Modifier.testTag("advanced_settings_sheet"),
    ) {
        AdvancedSettingsSheet(
            runtime = runtime,
            defaultThinkingEnabled = defaultThinkingEnabled,
            voiceState = voiceState,
            wifiOnlyDownloadsEnabled = downloadPreferences.wifiOnlyEnabled,
            onDefaultThinkingEnabledChanged = onDefaultThinkingEnabledChanged,
            presetBackingStore = presetBackingStore,
            onModelPresetSelected = onModelPresetSelected,
            onOpenPresetCustomization = onOpenPresetCustomization,
            onPerformanceProfileSelected = onPerformanceProfileSelected,
            onKeepAlivePreferenceSelected = onKeepAlivePreferenceSelected,
            onVoiceActivationChanged = onVoiceActivationChanged,
            onRequestAssistantRole = onRequestAssistantRole,
            onOpenBatteryOptimizationSettings = onOpenBatteryOptimizationSettings,
            onOpenAppSettings = onOpenAppSettings,
            onWifiOnlyDownloadsChanged = onWifiOnlyDownloadsChanged,
            onGpuAccelerationEnabledChanged = onGpuAccelerationEnabledChanged,
            onExportDiagnostics = onExportDiagnostics,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PresetCustomizationModalHost(
    activeSurface: ModalSurface,
    provisioningViewModel: ModelProvisioningViewModel,
    defaultGetReadyModelId: String?,
    presetBackingStore: PresetBackingStore,
    onDismissSurface: () -> Unit,
    onPresetBackingChanged: (ModelPreset, String) -> Unit,
    onResetPresetMappings: () -> Unit,
) {
    if (activeSurface !is ModalSurface.PresetCustomization) {
        return
    }

    val provisioningState by provisioningViewModel.uiState.collectAsState()
    val modelLibraryState = remember(provisioningState, defaultGetReadyModelId) {
        provisioningState.toModelLibraryUiState(defaultGetReadyModelId)
    } ?: return
    val presetSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppBottomSheet(
        title = stringResource(id = R.string.ui_preset_customize_title),
        sheetState = presetSheetState,
        onDismiss = onDismissSurface,
    ) {
        PresetCustomizationSheetContent(
            libraryState = modelLibraryState,
            presetBackingStore = presetBackingStore,
            onBackingModelSelected = onPresetBackingChanged,
            onResetToDefaults = onResetPresetMappings,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CompletionSettingsModalHost(
    activeSurface: ModalSurface,
    viewModel: ChatViewModel,
    onDismissSurface: () -> Unit,
    onCompletionSettingsChanged: (CompletionSettings) -> Unit,
) {
    if (activeSurface !is ModalSurface.CompletionSettings) {
        return
    }

    val completionSettings by viewModel.currentCompletionSettingsFlow.collectAsState()
    SettingsDestination(
        title = stringResource(id = R.string.ui_completion_settings_title),
        onClose = onDismissSurface,
    ) {
        CompletionSettingsSheet(
            settings = completionSettings,
            onSettingsChanged = onCompletionSettingsChanged,
            onClose = onDismissSurface,
        )
    }
}

private fun ModalSurface.isSettingsDestination(): Boolean = when (this) {
    ModalSurface.AdvancedSettings, ModalSurface.CompletionSettings -> true
    else -> false
}

@Composable
@Suppress("LongParameterList")
private fun OnboardingModalHost(
    activeSurface: ModalSurface,
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
    defaultGetReadyModelId: String?,
    pendingGetReadyActivation: Pair<String, String>?,
    setupFailureMessage: String?,
    setupRequestInFlight: Boolean,
    modelLoadingState: ModelLoadingState,
    sendReady: Boolean,
    onOnboardingPageChanged: (Int) -> Unit,
    onNextOnboardingPage: () -> Unit,
    onSkipOnboarding: () -> Unit,
    onFinishOnboarding: () -> Unit,
    onStartOnboardingDownload: () -> Unit,
    onChooseAnotherModel: () -> Unit,
) {
    if (activeSurface !is ModalSurface.Onboarding) {
        return
    }

    val onboardingPage by viewModel.onboardingPageFlow.collectAsState()
    val provisioningState by provisioningViewModel.uiState.collectAsState()
    val setupState = remember(
        defaultGetReadyModelId,
        provisioningState,
        pendingGetReadyActivation,
        setupFailureMessage,
        setupRequestInFlight,
        modelLoadingState,
        sendReady,
    ) {
        resolveOnboardingSetupUiState(
            defaultModelId = defaultGetReadyModelId,
            manifest = provisioningState.manifest,
            provisioningSnapshot = provisioningState.snapshot,
            downloads = provisioningState.downloads,
            pendingActivation = pendingGetReadyActivation,
            modelLoadingState = modelLoadingState,
            sendReady = sendReady,
            setupFailureMessage = setupFailureMessage,
            setupRequestInFlight = setupRequestInFlight,
        )
    }
    OnboardingScreen(
        currentPage = onboardingPage,
        onPageChanged = onOnboardingPageChanged,
        onNextPage = onNextOnboardingPage,
        onSkip = onSkipOnboarding,
        onFinish = onFinishOnboarding,
        setupState = setupState,
        onStartDownload = onStartOnboardingDownload,
        onContinueInBackground = onFinishOnboarding,
        onReviewSetup = {
            onFinishOnboarding()
            viewModel.showSurface(ModalSurface.ModelLibrary)
        },
        onChooseAnotherModel = onChooseAnotherModel,
    )
}

@Composable
private fun RoutingModeSwitchDialog(
    pending: Pair<String, String>?,
    onDismiss: () -> Unit,
    onConfirm: (modelId: String, version: String) -> Unit,
) {
    pending?.let { (modelId, version) ->
        ConfirmDialog(
            title = stringResource(id = R.string.ui_switch_model_title),
            text = stringResource(id = R.string.ui_switch_model_body, modelId, version),
            confirmLabel = stringResource(id = R.string.ui_load),
            dismissLabel = stringResource(id = R.string.ui_later),
            onConfirm = { onConfirm(modelId, version) },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun MeteredDownloadWarningDialog(
    pending: ModelDistributionVersion?,
    onDismiss: () -> Unit,
    onConfirm: (ModelDistributionVersion) -> Unit,
) {
    pending?.let { version ->
        ConfirmDialog(
            title = stringResource(id = R.string.ui_large_download_metered_title),
            text = stringResource(
                id = R.string.ui_large_download_metered_body,
                version.modelId,
                version.fileSizeBytes.formatAsGiB(),
            ),
            confirmLabel = stringResource(id = R.string.ui_large_download_metered_continue),
            dismissLabel = stringResource(id = R.string.ui_cancel_button),
            onConfirm = { onConfirm(version) },
            onDismiss = onDismiss,
        )
    }
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
