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
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import com.pocketagent.android.R
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.voice.VoiceActivationEnableResult
import com.pocketagent.android.voice.VoiceActivationController
import com.pocketagent.android.voice.VoiceActivationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ChatAppLaunchers(
    val launchImageAttachmentPicker: () -> Unit,
    val launchModelImportPicker: () -> Unit,
    val launchDownloadFlow: (ModelDistributionVersion) -> Unit,
    val toggleVoiceActivation: (Boolean) -> Unit,
    val requestAssistantRole: () -> Unit,
    val openBatteryOptimizationSettings: () -> Unit,
    val openAppSettings: () -> Unit,
)

@Composable
internal fun rememberChatAppLaunchers(
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    appViewModel: ChatAppViewModel,
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
    voiceController: VoiceActivationController,
    voiceState: VoiceActivationUiState,
): ChatAppLaunchers {
    val launchImageAttachmentPicker = rememberChatAppImageAttachmentLauncher(
        context = context,
        scope = scope,
        snackbarHostState = snackbarHostState,
        onAttachImage = viewModel::addAttachedImage,
    )
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val version = appViewModel.pendingNotificationPermissionVersion.value
        appViewModel.setPendingNotificationPermissionVersion(null)
        if (version == null) {
            return@rememberLauncherForActivityResult
        }
        if (!granted) {
            provisioningViewModel.setStatusMessage(
                context.getString(R.string.ui_model_download_notifications_disabled),
            )
        }
        beginDownload(
            context = context,
            scope = scope,
            provisioningViewModel = provisioningViewModel,
            version = version,
        )
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            handleVoiceActivationResult(
                result = voiceController.setEnabled(true),
                voiceController = voiceController,
                context = context,
                scope = scope,
                snackbarHostState = snackbarHostState,
            )
        } else {
            voiceController.refresh()
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
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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
                viewModel.refreshRuntimeReadiness(statusDetailOverride = statusMessage)
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

            else -> beginDownload(
                context = context,
                scope = scope,
                provisioningViewModel = provisioningViewModel,
                version = version,
            )
        }
    }
    val toggleVoiceActivation: (Boolean) -> Unit = { enabled ->
        when {
            !enabled -> voiceController.setEnabled(false)
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else -> handleVoiceActivationResult(
                result = voiceController.setEnabled(true),
                voiceController = voiceController,
                context = context,
                scope = scope,
                snackbarHostState = snackbarHostState,
            )
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
        launchModelImportPicker = { modelPicker.launch(arrayOf("*/*")) },
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

internal fun voiceActivationFeedback(
    result: VoiceActivationEnableResult,
    lastError: String? = null,
): VoiceActivationFeedback? {
    return when (result) {
        VoiceActivationEnableResult.BLOCKED_MODELS_MISSING ->
            VoiceActivationFeedback(messageResId = R.string.ui_voice_activation_models_missing)
        VoiceActivationEnableResult.BLOCKED_MICROPHONE_PERMISSION ->
            VoiceActivationFeedback(messageResId = R.string.ui_voice_activation_microphone_required)
        VoiceActivationEnableResult.START_FAILED ->
            lastError?.takeIf { it.isNotBlank() }?.let { VoiceActivationFeedback(messageText = it) }
        else -> null
    }
}

private fun beginDownload(
    context: Context,
    scope: CoroutineScope,
    provisioningViewModel: ModelProvisioningViewModel,
    version: ModelDistributionVersion,
) {
    scope.launch {
        runCatching { provisioningViewModel.enqueueDownload(version) }
            .onSuccess { taskId ->
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
                provisioningViewModel.setStatusMessage(
                    context.getString(
                        R.string.ui_model_download_start_failed,
                        version.modelId,
                        version.version,
                        error.message ?: "unknown error",
                    ),
                )
            }
    }
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
