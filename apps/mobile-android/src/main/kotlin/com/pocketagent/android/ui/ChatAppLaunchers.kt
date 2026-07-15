package com.pocketagent.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.pocketagent.android.R
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.voice.VoiceActivationEnableResult
import com.pocketagent.android.voice.VoiceActivationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ChatAppLaunchers(
    val launchImageAttachmentPicker: () -> Unit,
    val launchModelImportPicker: () -> Unit,
    val launchDownloadFlow: suspend (ModelDistributionVersion) -> Unit,
    val toggleVoiceActivation: (Boolean) -> Unit,
    val requestAssistantRole: () -> Unit,
    val openBatteryOptimizationSettings: () -> Unit,
    val openAppSettings: () -> Unit,
)

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun rememberChatAppLaunchers(
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    appViewModel: ChatAppViewModel,
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
    voiceController: VoiceActivationController,
): ChatAppLaunchers {
    val launchImageAttachmentPicker = rememberChatAppImageAttachmentLauncher(
        context = context,
        scope = scope,
        snackbarHostState = snackbarHostState,
        onAttachImage = viewModel::addAttachedImage,
    )
    val launchModelImportPicker = rememberModelImportLauncher(
        context = context,
        appViewModel = appViewModel,
        provisioningViewModel = provisioningViewModel,
    )
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        appViewModel.setPendingNotificationPermissionVersion(null)
        if (!granted) {
            provisioningViewModel.setStatusMessage(
                context.getString(R.string.ui_model_download_notifications_disabled),
            )
        }
    }
    var pendingVoiceEnable by remember { mutableStateOf(false) }
    lateinit var continueVoiceSetup: () -> Unit
    val assistantRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        voiceController.refresh()
        if (pendingVoiceEnable) {
            if (!voiceController.observe().value.betaContract.needsAssistantRole) {
                handleVoiceActivationResult(
                    result = voiceController.setEnabled(true),
                    voiceController = voiceController,
                    context = context,
                    scope = scope,
                    snackbarHostState = snackbarHostState,
                )
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.ui_voice_activation_assistant_required),
                    )
                }
            }
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            continueVoiceSetup()
        } else {
            voiceController.refresh()
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.ui_voice_activation_microphone_required))
            }
        }
    }
    val voiceNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            when (
                nextVoiceActivationPermissionStep(
                    sdkInt = Build.VERSION.SDK_INT,
                    notificationPermissionGranted = true,
                    microphonePermissionGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                )
            ) {
                VoiceActivationPermissionStep.REQUEST_NOTIFICATIONS -> voiceController.refresh()
                VoiceActivationPermissionStep.REQUEST_MICROPHONE ->
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                VoiceActivationPermissionStep.ENABLE -> continueVoiceSetup()
            }
        } else {
            voiceController.refresh()
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.ui_voice_activation_notifications_required),
                    actionLabel = context.getString(R.string.ui_voice_activation_open_app_settings),
                )
                if (result == SnackbarResult.ActionPerformed) {
                    context.startActivity(voiceController.openAppSettingsIntent())
                }
            }
        }
    }
    val launchDownloadFlow: suspend (ModelDistributionVersion) -> Unit = { version ->
        when {
            provisioningViewModel.shouldWarnForMeteredLargeDownloadAsync(version) -> {
                appViewModel.setPendingMeteredWarningVersion(version)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                val started = beginDownload(
                    context = context,
                    provisioningViewModel = provisioningViewModel,
                    appViewModel = appViewModel,
                    version = version,
                )
                if (started) {
                    appViewModel.setPendingNotificationPermissionVersion(version)
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            else -> beginDownload(
                context = context,
                provisioningViewModel = provisioningViewModel,
                appViewModel = appViewModel,
                version = version,
            )
        }
    }
    continueVoiceSetup = {
        voiceController.refresh()
        if (voiceController.observe().value.betaContract.needsAssistantRole) {
            val roleIntent = voiceController.requestAssistantRoleIntent()
            if (roleIntent != null) {
                assistantRoleLauncher.launch(roleIntent)
            } else {
                handleVoiceActivationResult(
                    result = VoiceActivationEnableResult.BLOCKED_ASSISTANT_NOT_SELECTED,
                    voiceController = voiceController,
                    context = context,
                    scope = scope,
                    snackbarHostState = snackbarHostState,
                )
            }
        } else {
            handleVoiceActivationResult(
                result = voiceController.setEnabled(true),
                voiceController = voiceController,
                context = context,
                scope = scope,
                snackbarHostState = snackbarHostState,
            )
        }
    }
    val toggleVoiceActivation: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            pendingVoiceEnable = false
            voiceController.setEnabled(false)
        } else {
            pendingVoiceEnable = true
            when (
                nextVoiceActivationPermissionStep(
                    sdkInt = Build.VERSION.SDK_INT,
                    notificationPermissionGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                    microphonePermissionGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                )
            ) {
                VoiceActivationPermissionStep.REQUEST_NOTIFICATIONS ->
                    voiceNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                VoiceActivationPermissionStep.REQUEST_MICROPHONE ->
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                VoiceActivationPermissionStep.ENABLE -> continueVoiceSetup()
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
            context.startActivity(voiceController.openBatteryOptimizationSettingsIntent())
        }.onFailure {
            context.startActivity(voiceController.openAppSettingsIntent())
        }
    }
    val openAppSettings: () -> Unit = {
        context.startActivity(voiceController.openAppSettingsIntent())
    }
    return ChatAppLaunchers(
        launchImageAttachmentPicker = launchImageAttachmentPicker,
        launchModelImportPicker = launchModelImportPicker,
        launchDownloadFlow = launchDownloadFlow,
        toggleVoiceActivation = toggleVoiceActivation,
        requestAssistantRole = requestAssistantRole,
        openBatteryOptimizationSettings = openBatteryOptimizationSettings,
        openAppSettings = openAppSettings,
    )
}

private fun handleVoiceActivationResult(
    result: VoiceActivationEnableResult,
    voiceController: VoiceActivationController,
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
) {
    val feedback = voiceActivationFeedback(
        result = result,
        lastError = voiceController.observe().value.settings.lastError,
    ) ?: return
    scope.launch {
        val message = feedback.messageText ?: feedback.messageResId?.let(context::getString) ?: return@launch
        snackbarHostState.showSnackbar(message)
    }
}

internal data class VoiceActivationFeedback(
    @StringRes val messageResId: Int? = null,
    val messageText: String? = null,
)

internal enum class VoiceActivationPermissionStep {
    REQUEST_NOTIFICATIONS,
    REQUEST_MICROPHONE,
    ENABLE,
}

internal fun nextVoiceActivationPermissionStep(
    sdkInt: Int,
    notificationPermissionGranted: Boolean,
    microphonePermissionGranted: Boolean,
): VoiceActivationPermissionStep {
    return when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted ->
            VoiceActivationPermissionStep.REQUEST_NOTIFICATIONS
        !microphonePermissionGranted -> VoiceActivationPermissionStep.REQUEST_MICROPHONE
        else -> VoiceActivationPermissionStep.ENABLE
    }
}

internal fun voiceActivationFeedback(
    result: VoiceActivationEnableResult,
    lastError: String? = null,
): VoiceActivationFeedback? {
    return when (result) {
        VoiceActivationEnableResult.BLOCKED_NOTIFICATION_PERMISSION ->
            VoiceActivationFeedback(messageResId = R.string.ui_voice_activation_notifications_required)
        VoiceActivationEnableResult.BLOCKED_MODELS_MISSING ->
            VoiceActivationFeedback(messageResId = R.string.ui_voice_activation_models_missing)
        VoiceActivationEnableResult.BLOCKED_MICROPHONE_PERMISSION ->
            VoiceActivationFeedback(messageResId = R.string.ui_voice_activation_microphone_required)
        VoiceActivationEnableResult.BLOCKED_ASSISTANT_NOT_SELECTED ->
            VoiceActivationFeedback(messageResId = R.string.ui_voice_activation_assistant_required)
        VoiceActivationEnableResult.SETUP_STARTED ->
            VoiceActivationFeedback(messageResId = R.string.ui_voice_activation_setup_started)
        VoiceActivationEnableResult.START_FAILED ->
            lastError?.takeIf { it.isNotBlank() }?.let { VoiceActivationFeedback(messageText = it) }
        else -> null
    }
}

private suspend fun beginDownload(
    context: Context,
    provisioningViewModel: ModelProvisioningViewModel,
    appViewModel: ChatAppViewModel,
    version: ModelDistributionVersion,
): Boolean {
    return runCatching { provisioningViewModel.enqueueDownload(version) }
        .onSuccess { taskId ->
            if (appViewModel.pendingGetReadyActivation.value == (version.modelId to version.version)) {
                appViewModel.setGetReadySetupFailure(null)
            }
            provisioningViewModel.setStatusMessage(
                context.getString(
                    R.string.ui_model_download_enqueued,
                    version.modelId,
                    version.version,
                    taskId,
                ),
            )
        }
        .onFailure { error ->
            val message = context.getString(
                R.string.ui_model_download_start_failed,
                version.modelId,
                version.version,
                error.message ?: "unknown error",
            )
            provisioningViewModel.setStatusMessage(message)
            if (appViewModel.pendingGetReadyActivation.value == (version.modelId to version.version)) {
                appViewModel.setPendingGetReadyActivation(null)
                appViewModel.setGetReadySetupFailure(message)
            }
        }
        .isSuccess
}

@Composable
private fun rememberChatAppImageAttachmentLauncher(
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onAttachImage: (String) -> Unit,
): () -> Unit {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val localPath = copySelectedImageToLocal(context, uri)
                if (localPath != null) {
                    onAttachImage(localPath)
                } else {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.ui_image_attach_failed),
                    )
                }
            }
        }
    }
    return { imagePicker.launch("image/*") }
}

private suspend fun copySelectedImageToLocal(context: Context, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val mimeType = context.contentResolver.getType(uri)
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?.lowercase()
                ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
                ?: "jpg"
            val imagesDir = java.io.File(context.cacheDir, "attached_images").apply { mkdirs() }
            cleanupStaleAttachedImages(imagesDir)
            val target = java.io.File(imagesDir, "img_${System.currentTimeMillis()}.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            if (target.length() == 0L) {
                target.delete()
                return@runCatching null
            }
            target.absolutePath
        }.getOrNull()
    }
}

private fun cleanupStaleAttachedImages(imagesDir: java.io.File) {
    val staleThresholdMs = 60 * 60 * 1000L
    imagesDir.listFiles()?.forEach { file ->
        if (System.currentTimeMillis() - file.lastModified() > staleThresholdMs) {
            file.delete()
        }
    }
}
