package com.pocketagent.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketagent.android.BuildConfig
import com.pocketagent.android.R
import com.pocketagent.android.runtime.MODEL_OFFLOAD_REASON_MANUAL
import com.pocketagent.android.runtime.modelSpecProviderForContext
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.voice.VoiceActivationController
import com.pocketagent.android.ui.state.ChatGatePrimaryAction
import com.pocketagent.android.ui.state.ChatGateState
import com.pocketagent.android.ui.state.ChatGateStatus
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.activeOrRequestedModel
import com.pocketagent.android.ui.state.resolveChatGateState
import com.pocketagent.core.ModelPreset
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.ModelInteractionRegistry
import com.pocketagent.runtime.ThinkingSupport
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun PocketAgentApp(
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val modelLoadingState by viewModel.modelLoadingState.collectAsState()
    val provisioningState by provisioningViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val downloads = provisioningState.downloads
    if (provisioningState.snapshot == null) {
        return
    }
    val chatGateState by derivedStateOf {
        val snap = provisioningState.snapshot ?: return@derivedStateOf ChatGateState(
            status = ChatGateStatus.BLOCKED_MODEL_MISSING,
            primaryAction = ChatGatePrimaryAction.OPEN_MODEL_SETUP,
        )
        resolveChatGateState(
            runtime = state.runtime,
            provisioningSnapshot = snap,
            advancedUnlocked = state.advancedUnlocked,
        )
    }
    val presetRevision by viewModel.presetBackingStore.revisionFlow().collectAsState(initial = 0L)
    val headerUiState by derivedStateOf {
        presetRevision
        deriveChatHeaderUiState(
            modelLoadingState = modelLoadingState,
            routingMode = state.runtime.routingMode,
            presetBackingStore = viewModel.presetBackingStore,
        )
    }
    val activeRuntimeModelLabel = headerUiState.activeRuntimeModelLabel
    val activeModelId by derivedStateOf {
        modelLoadingState.loadedModel?.modelId ?: state.runtime.activeModelId
    }
    val canAttachImages = canAttachImagesForModel(activeModelId)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val currentActiveSurface by rememberUpdatedState(state.activeSurface)
    val interactionRegistry = remember(context) {
        ModelInteractionRegistry(
            specProvider = modelSpecProviderForContext(context),
        )
    }
    val thinkingToggleModelId by derivedStateOf {
        modelLoadingState.loadedModel?.modelId ?: state.runtime.activeModelId
    }
    val showThinkingToggle by derivedStateOf {
        thinkingToggleModelId?.let { modelId ->
            runCatching {
                interactionRegistry.interactionProfileForModel(modelId).thinkingSupport == ThinkingSupport.THINK_TAGS
            }.getOrDefault(false)
        } == true
    }
    val canLoadLastUsedModel = headerUiState.canLoadLastUsedModel
    val lastUsedModelLabel = headerUiState.lastUsedModelLabel
    val appViewModel: ChatAppViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val voiceController = remember(context) { VoiceActivationController(context.applicationContext) }
    val voiceState by voiceController.observe().collectAsState()
    val sessionDeleteUndoState = rememberSessionDeleteUndoState(
        snackbarHostState = snackbarHostState,
        onCommitDelete = viewModel::deleteSession,
    )
    val isOffline = rememberIsOffline()
    val selectedModelIdForImport by appViewModel.selectedModelIdForImport.collectAsState()
    val pendingGetReadyActivation by appViewModel.pendingGetReadyActivation.collectAsState()
    val pendingMeteredWarningVersion by appViewModel.pendingMeteredWarningVersion.collectAsState()
    val pendingNotificationPermissionVersion by appViewModel.pendingNotificationPermissionVersion.collectAsState()
    val pendingRoutingModeSwitch by appViewModel.pendingRoutingModeSwitch.collectAsState()
    val lastDownloadTransitionRefreshKey by appViewModel.lastDownloadTransitionRefreshKey.collectAsState()
    val readinessRefreshSequence by appViewModel.readinessRefreshSequence.collectAsState()
    val defaultGetReadyModelId = remember { resolveDefaultGetReadyModelId(isDebugBuild = BuildConfig.DEBUG) }
    val modelLibraryState = remember(provisioningState, defaultGetReadyModelId) {
        provisioningState.toModelLibraryUiState(defaultGetReadyModelId)
    } ?: return
    val runtimeModelState = remember(provisioningState) {
        provisioningState.toRuntimeModelUiState()
    } ?: return
    val modelRemoveUndoState = rememberModelRemoveUndoState(
        snackbarHostState = snackbarHostState,
        onCommitRemove = { modelId, version ->
            scope.launch {
                val model = modelLibraryState.snapshot.models
                    .firstOrNull { it.modelId == modelId }
                val targetVersion = model?.installedVersions
                    ?.firstOrNull { it.version == version }
                val removePlan = if (model != null && targetVersion != null) {
                    resolveRemoveVersionPlan(
                        model = model,
                        version = targetVersion,
                        loadedModel = modelLoadingState.loadedModel,
                    )
                } else {
                    null
                }
                if (removePlan?.isBlockedByActiveSelection == true) {
                    provisioningViewModel.setStatusMessage(
                        context.getString(R.string.ui_model_version_remove_failed),
                    )
                    return@launch
                }
                if (removePlan?.requiresOffload == true) {
                    provisioningViewModel.offloadModel(reason = MODEL_OFFLOAD_REASON_MANUAL)
                }
                if (removePlan?.requiresClearingActiveSelection == true) {
                    provisioningViewModel.clearActiveVersionAsync(modelId)
                }
                val removed = provisioningViewModel.removeVersionAsync(modelId, version)
                val statusMessage = if (removed) {
                    context.getString(R.string.ui_model_version_removed, modelId, version)
                } else {
                    context.getString(R.string.ui_model_version_remove_failed)
                }
                if (removed) {
                    viewModel.refreshRuntimeReadiness(statusDetailOverride = statusMessage)
                }
                provisioningViewModel.setStatusMessage(statusMessage)
            }
        },
    )
    val beginDownload: (ModelDistributionVersion) -> Unit = { version ->
        scope.launch {
            startModelDownload(
                context = context,
                version = version,
                enqueueDownload = { selected -> provisioningViewModel.enqueueDownload(selected) },
                onStatus = { message -> provisioningViewModel.setStatusMessage(message) },
            )
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val version = pendingNotificationPermissionVersion
        appViewModel.setPendingNotificationPermissionVersion(null)
        if (version == null) {
            return@rememberLauncherForActivityResult
        }
        if (!granted) {
            provisioningViewModel.setStatusMessage(
                context.getString(R.string.ui_model_download_notifications_disabled),
            )
        }
        beginDownload(version)
    }
    val launchDownloadFlow: (ModelDistributionVersion) -> Unit = { version ->
        when {
            provisioningViewModel.shouldWarnForMeteredLargeDownload(version) -> {
                appViewModel.setPendingMeteredWarningVersion(version)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                appViewModel.setPendingNotificationPermissionVersion(version)
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            else -> beginDownload(version)
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceController.setEnabled(true)
            if (!voiceState.batteryOptimizationIgnored) {
                runCatching {
                    context.startActivity(voiceController.requestBatteryOptimizationIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.ui_voice_activation_microphone_required))
            }
        }
    }
    val assistantRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        voiceController.refresh()
    }
    val openModelSheet: () -> Unit = {
        viewModel.showSurface(ModalSurface.ModelLibrary)
    }
    val toggleVoiceActivation: (Boolean) -> Unit = { enabled ->
        when {
            !enabled -> voiceController.setEnabled(false)
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else -> {
                voiceController.setEnabled(true)
                if (!voiceState.batteryOptimizationIgnored) {
                    runCatching {
                        context.startActivity(voiceController.requestBatteryOptimizationIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }
    val requestAssistantRole: () -> Unit = {
        val roleIntent = voiceController.requestAssistantRoleIntent()
        if (roleIntent != null) {
            assistantRoleLauncher.launch(roleIntent)
        }
    }
    val openBatteryOptimizationSettings: () -> Unit = {
        runCatching {
            context.startActivity(voiceController.requestBatteryOptimizationIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            context.startActivity(voiceController.openAppSettingsIntent())
        }
    }
    val openAppSettings: () -> Unit = {
        context.startActivity(voiceController.openAppSettingsIntent())
    }
    val showBusyModelOperationFeedback: () -> Unit = {
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.ui_model_operation_already_in_progress))
        }
    }
    val loadModelVersionAction: (String, String, Boolean) -> Unit = { modelId, version, closeOnSuccess ->
        scope.launch {
            val result = viewModel.loadModel(modelId = modelId, version = version)
            if (result == null) {
                showBusyModelOperationFeedback()
                return@launch
            }
            provisioningViewModel.setStatusMessage(
                lifecycleStatusMessage(
                    context = context,
                    result = result,
                    fallbackModelId = modelId,
                    fallbackVersion = version,
                ),
            )
            if (result.success && closeOnSuccess) {
                viewModel.dismissSurface()
            }
        }
    }
    val loadLastUsedModelAction: (Boolean) -> Unit = { closeOnSuccess ->
        scope.launch {
            val result = viewModel.loadLastUsedModel()
            if (result == null) {
                showBusyModelOperationFeedback()
                return@launch
            }
            provisioningViewModel.setStatusMessage(
                lifecycleStatusMessage(
                    context = context,
                    result = result,
                    fallbackModelId = modelLoadingState.lastUsedModel?.modelId,
                    fallbackVersion = modelLoadingState.lastUsedModel?.modelVersion,
                ),
            )
            if (result.success && closeOnSuccess) {
                viewModel.dismissSurface()
            }
        }
    }
    val offloadModelAction: (Boolean) -> Unit = { closeOnSuccess ->
        scope.launch {
            val result = viewModel.offloadModel(reason = MODEL_OFFLOAD_REASON_MANUAL)
            if (result == null) {
                showBusyModelOperationFeedback()
                return@launch
            }
            provisioningViewModel.setStatusMessage(
                lifecycleStatusMessage(
                    context = context,
                    result = result,
                    fallbackModelId = modelLoadingState.activeOrRequestedModel()?.modelId,
                    fallbackVersion = modelLoadingState.activeOrRequestedModel()?.modelVersion,
                ),
            )
            if (result.success && closeOnSuccess) {
                viewModel.dismissSurface()
            }
        }
    }
    val refreshAction: () -> Unit = {
        viewModel.refreshRuntimeReadiness()
        provisioningViewModel.refreshSnapshot()
        scope.launch { provisioningViewModel.refreshManifest() }
        provisioningViewModel.setStatusMessage(
            context.getString(R.string.ui_model_refresh_runtime_feedback),
        )
    }
    val runGetReadyFlow: () -> Unit = {
        scope.launch {
            viewModel.onGetReadyTapped()
            provisioningViewModel.setStatusMessage(context.getString(R.string.ui_get_ready_started_status))
            provisioningViewModel.refreshManifest()
            val manifest = provisioningViewModel.uiState.value.manifest
            val defaultVersion = resolveDefaultGetReadyVersion(
                manifest = manifest,
                defaultModelId = defaultGetReadyModelId,
            )
            if (defaultVersion == null) {
                provisioningViewModel.setStatusMessage(
                    context.getString(R.string.ui_model_downloads_manifest_empty),
                )
                openModelSheet()
                return@launch
            }

            val existingVersion = provisioningViewModel.listInstalledVersionsAsync(
                modelId = defaultVersion.modelId,
            ).firstOrNull { it.version == defaultVersion.version }

            if (existingVersion != null) {
                provisioningViewModel.setActiveVersionAsync(
                    modelId = defaultVersion.modelId,
                    version = defaultVersion.version,
                )
                val loadResult = viewModel.loadModel(
                    modelId = defaultVersion.modelId,
                    version = defaultVersion.version,
                )
                loadResult?.let { result ->
                    provisioningViewModel.setStatusMessage(
                        lifecycleStatusMessage(
                            context = context,
                            result = result,
                            fallbackModelId = defaultVersion.modelId,
                            fallbackVersion = defaultVersion.version,
                        ),
                    )
                } ?: showBusyModelOperationFeedback()
                return@launch
            }

            appViewModel.setPendingGetReadyActivation(defaultVersion.modelId to defaultVersion.version)
            launchDownloadFlow(defaultVersion)
            openModelSheet()
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
        voiceController.refresh()
    }
    val queueLoadForRoutingMode: (RoutingMode) -> Unit = { mode ->
        val targetModelId = ModelCatalog.modelIdForRoutingMode(mode)
        val targetVersion = modelLibraryState.snapshot.models
            .firstOrNull { model -> model.modelId == targetModelId }
            ?.installedVersions
            ?.firstOrNull { version -> version.isActive }
            ?.version
            ?: modelLibraryState.snapshot.models
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
        val matchedBefore = viewModel.presetBackingStore.presetMatchingRoutingMode(state.runtime.routingMode)
        viewModel.setCustomPresetBacking(preset, modelId)
        if (matchedBefore == preset) {
            handleRoutingModeSelected(viewModel.presetBackingStore.routingModeForPreset(preset))
        }
    }
    val onResetPresetMappings: () -> Unit = {
        val matched = viewModel.presetBackingStore.presetMatchingRoutingMode(state.runtime.routingMode)
        viewModel.resetPresetMappingsToDefaults()
        if (matched != null && matched != ModelPreset.AUTO) {
            handleRoutingModeSelected(viewModel.presetBackingStore.routingModeForPreset(matched))
        }
    }
    val launchImageAttachmentPicker = rememberImageAttachmentLauncher(
        context = context,
        scope = scope,
        snackbarHostState = snackbarHostState,
        onAttachImage = viewModel::addAttachedImage,
    )
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val modelId = appViewModel.selectedModelIdForImport.value ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_import_cancelled))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_import_in_progress))
            provisioningViewModel.importModelFromUri(
                modelId = modelId,
                sourceUri = uri,
            ).onSuccess { result ->
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
                viewModel.refreshRuntimeReadiness(
                    statusDetailOverride = statusMessage,
                )
                provisioningViewModel.setStatusMessage(statusMessage)
            }.onFailure { error ->
                provisioningViewModel.setStatusMessage(
                    context.getString(
                        R.string.ui_model_import_failure,
                        error.message ?: "Unknown import error",
                    ),
                )
            }
        }
    }

    BackHandler(
        enabled = state.activeSurface != ModalSurface.None || drawerState.currentValue == DrawerValue.Open,
    ) {
        when (state.activeSurface) {
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
        onActivateVersion = provisioningViewModel::setActiveVersionAsync,
        onLoadModel = viewModel::loadModel,
        onShowBusyModelOperationFeedback = showBusyModelOperationFeedback,
        onClearPendingGetReadyActivation = { appViewModel.setPendingGetReadyActivation(null) },
        onIncrementReadinessRefreshSequence = appViewModel::incrementReadinessRefreshSequence,
        onRefreshRuntimeReadiness = viewModel::refreshRuntimeReadiness,
        onSetLastDownloadTransitionRefreshKey = appViewModel::setLastDownloadTransitionRefreshKey,
        onOpenModelSheet = openModelSheet,
    )

    LaunchedEffect(state.activeSurface) {
        if (state.activeSurface !is ModalSurface.ModelLibrary) return@LaunchedEffect
        provisioningViewModel.refreshSnapshot()
    }

    LaunchedEffect(state.activeSurface) {
        if (state.activeSurface is ModalSurface.SessionDrawer) {
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
                SessionDrawer(
                    state = state,
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
                    lastUsedModelLabel = lastUsedModelLabel,
                    modelLibraryState = modelLibraryState,
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
                    composer = state.composer,
                    activeSessionId = state.activeSessionId,
                    canAttachImages = canAttachImages,
                    showThinkingToggle = showThinkingToggle,
                    thinkingEnabled = state.activeSession?.completionSettings?.showThinking == true,
                    onAttachImage = launchImageAttachmentPicker,
                    onBlockedAction = onBlockedAction,
                )
            },
        ) { innerPadding ->
            ChatScreenBody(
                state = state,
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
        activeSurface = state.activeSurface,
        modelLibraryState = modelLibraryState,
        runtimeModelState = runtimeModelState,
        modelLoadingState = modelLoadingState,
        routingMode = state.runtime.routingMode,
        presetBackingStore = viewModel.presetBackingStore,
        modelRemoveUndoState = modelRemoveUndoState,
        viewModel = viewModel,
        provisioningViewModel = provisioningViewModel,
        appViewModel = appViewModel,
        onLaunchImportPicker = {
            modelPicker.launch(arrayOf("*/*"))
        },
        onLaunchDownloadFlow = launchDownloadFlow,
        onLoadModelVersion = loadModelVersionAction,
        onLoadLastUsedModel = loadLastUsedModelAction,
        onOffloadModel = offloadModelAction,
    )

    ModalOrchestrator(
        state = state,
        voiceState = voiceState,
        provisioningState = provisioningState,
        modelLibraryState = modelLibraryState,
        presetBackingStore = viewModel.presetBackingStore,
        pendingRoutingModeSwitch = pendingRoutingModeSwitch,
        pendingMeteredWarningVersion = pendingMeteredWarningVersion,
        downloads = downloads,
        onDismissSurface = viewModel::dismissSurface,
        onUseToolPrompt = viewModel::prefillComposer,
        onDefaultThinkingEnabledChanged = viewModel::setDefaultThinkingEnabled,
        onModelPresetSelected = handlePresetSelected,
        onOpenPresetCustomization = { viewModel.showSurface(ModalSurface.PresetCustomization) },
        onPresetBackingChanged = onPresetBackingChanged,
        onResetPresetMappings = onResetPresetMappings,
        onPerformanceProfileSelected = viewModel::setPerformanceProfile,
        onKeepAlivePreferenceSelected = viewModel::setKeepAlivePreference,
        onVoiceActivationChanged = toggleVoiceActivation,
        onRequestAssistantRole = requestAssistantRole,
        onOpenBatteryOptimizationSettings = openBatteryOptimizationSettings,
        onOpenAppSettings = openAppSettings,
        onWifiOnlyDownloadsChanged = provisioningViewModel::setDownloadWifiOnlyEnabled,
        onGpuAccelerationEnabledChanged = viewModel::setGpuAccelerationEnabled,
        onExportDiagnostics = viewModel::exportDiagnostics,
        completionSettings = state.activeSession?.completionSettings ?: com.pocketagent.android.ui.state.CompletionSettings(),
        onCompletionSettingsChanged = viewModel::updateSessionCompletionSettings,
        onDismissRoutingModeSwitch = { appViewModel.setPendingRoutingModeSwitch(null) },
        onConfirmRoutingModeSwitch = { modelId, version ->
            appViewModel.setPendingRoutingModeSwitch(null)
            loadModelVersionAction(modelId, version, false)
        },
        onDismissMeteredDownloadWarning = { appViewModel.setPendingMeteredWarningVersion(null) },
        onConfirmMeteredDownloadWarning = { version ->
            provisioningViewModel.acknowledgeLargeDownloadCellularWarning()
            appViewModel.setPendingMeteredWarningVersion(null)
            launchDownloadFlow(version)
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
    composer: ComposerUiState,
    activeSessionId: String?,
    canAttachImages: Boolean,
    showThinkingToggle: Boolean,
    thinkingEnabled: Boolean,
    onAttachImage: () -> Unit,
    onBlockedAction: (ChatGatePrimaryAction) -> Unit,
) {
    val draftText by viewModel.composerDraftText.collectAsState()
    ComposerBar(
        text = draftText,
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

internal fun canAttachImagesForModel(modelId: String?): Boolean {
    return modelId?.let(ModelCatalog::isVisionCapable) == true
}
