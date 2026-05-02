package com.pocketagent.android.ui.state

import androidx.compose.runtime.Immutable
import com.pocketagent.android.runtime.ProvisioningReadiness
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot

enum class ChatGateStatus {
    READY,
    BLOCKED_MODEL_MISSING,
    BLOCKED_RUNTIME_CHECK,
    LOADING_MODEL,
    ERROR_RECOVERABLE,
}

enum class ChatGatePrimaryAction {
    NONE,
    GET_READY,
    OPEN_MODEL_SETUP,
    REFRESH_RUNTIME_CHECKS,
}

@Immutable
data class ChatGateState(
    val status: ChatGateStatus,
    val primaryAction: ChatGatePrimaryAction,
    val detail: String? = null,
) {
    val isReady: Boolean
        get() = status == ChatGateStatus.READY
}

fun resolveChatGateState(
    runtime: RuntimeUiState,
    provisioningSnapshot: RuntimeProvisioningSnapshot?,
    advancedUnlocked: Boolean,
): ChatGateState {
    if (
        runtime.modelRuntimeStatus == ModelRuntimeStatus.LOADING ||
        runtime.startupProbeState == StartupProbeState.RUNNING
    ) {
        return ChatGateState(
            status = ChatGateStatus.LOADING_MODEL,
            primaryAction = ChatGatePrimaryAction.NONE,
            detail = runtime.modelStatusDetail,
        )
    }

    val recoverySignal = provisioningSnapshot
        ?.recoverableCorruptions
        ?.firstOrNull { signal ->
            signal.code == "MODEL_LOCAL_FILE_MISSING" ||
                signal.code == "MODEL_PATH_ALIAS_STALE"
        }
    if (recoverySignal != null) {
        return ChatGateState(
            status = ChatGateStatus.ERROR_RECOVERABLE,
            primaryAction = ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS,
            detail = recoverySignal.message,
        )
    }

    val runtimeBlocked = runtime.startupProbeState == StartupProbeState.BLOCKED ||
        runtime.startupProbeState == StartupProbeState.BLOCKED_TIMEOUT
    val provisioningBlocked = provisioningSnapshot?.readiness == ProvisioningReadiness.BLOCKED
    val missingRequiredModel = provisioningSnapshot?.missingRequiredModelIds?.isNotEmpty() == true
    val runtimeModelReady = runtime.modelRuntimeStatus == ModelRuntimeStatus.READY

    // Disk/catalog "blocked" reflects startup-candidate coverage only. Once the native runtime
    // reports a loaded model, chat must unblock — users may run non-candidate or dynamically
    // admitted models that do not satisfy verifiedActiveModelCount.
    if ((provisioningBlocked || missingRequiredModel) && !runtimeModelReady) {
        return ChatGateState(
            status = ChatGateStatus.BLOCKED_MODEL_MISSING,
            primaryAction = if (advancedUnlocked) {
                ChatGatePrimaryAction.OPEN_MODEL_SETUP
            } else {
                ChatGatePrimaryAction.GET_READY
            },
            detail = runtime.modelStatusDetail,
        )
    }

    if (runtimeBlocked || runtime.modelRuntimeStatus == ModelRuntimeStatus.NOT_READY) {
        return ChatGateState(
            status = ChatGateStatus.BLOCKED_RUNTIME_CHECK,
            primaryAction = ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS,
            detail = runtime.modelStatusDetail,
        )
    }

    if (runtime.lastErrorCode != null || runtime.modelRuntimeStatus == ModelRuntimeStatus.ERROR) {
        val primaryAction = if (runtime.startupProbeState == StartupProbeState.BLOCKED_TIMEOUT) {
            ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS
        } else if (advancedUnlocked) {
            ChatGatePrimaryAction.OPEN_MODEL_SETUP
        } else {
            ChatGatePrimaryAction.GET_READY
        }
        return ChatGateState(
            status = ChatGateStatus.ERROR_RECOVERABLE,
            primaryAction = primaryAction,
            detail = runtime.lastErrorUserMessage ?: runtime.modelStatusDetail,
        )
    }

    return ChatGateState(
        status = ChatGateStatus.READY,
        primaryAction = ChatGatePrimaryAction.NONE,
        detail = null,
    )
}
