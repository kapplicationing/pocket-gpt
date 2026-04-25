package com.pocketagent.android.runtime

import android.content.Context
import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

data class ProvisioningAggregateState(
    val snapshot: RuntimeProvisioningSnapshot = RuntimeProvisioningSnapshot.empty(),
    val downloads: List<DownloadTaskState> = emptyList(),
    val downloadPreferences: DownloadPreferencesState = DownloadPreferencesState(),
    val lifecycle: RuntimeModelLifecycleSnapshot = RuntimeModelLifecycleSnapshot.initial(),
    val manifest: ModelDistributionManifest = ModelDistributionManifest(models = emptyList()),
    val manifestLoaded: Boolean = false,
)

internal class DefaultProvisioningAggregateStore(
    context: Context,
    coroutineScope: CoroutineScope,
    private val runtimeBindings: ProvisioningRuntimeBindings = appRuntimeProvisioningBindings(context),
) {
    private val downloads = runtimeBindings.observeDownloads()
    private val downloadPreferences = runtimeBindings.observeDownloadPreferences()
    private val lifecycle = runtimeBindings.observeModelLifecycle()
    private val _state = MutableStateFlow(
        ProvisioningAggregateState(
            snapshot = runtimeBindings.currentProvisioningSnapshot(),
            downloads = downloads.value,
            downloadPreferences = runtimeBindings.currentDownloadPreferences(),
            lifecycle = runtimeBindings.currentModelLifecycle(),
        ),
    )

    init {
        coroutineScope.launch {
            downloads.collect { tasks ->
                mutate { current ->
                    current.copy(
                        downloads = tasks,
                    )
                }
            }
        }
        coroutineScope.launch {
            downloadPreferences.collect { preferences ->
                mutate { current -> current.copy(downloadPreferences = preferences) }
            }
        }
        coroutineScope.launch {
            lifecycle.collect { currentLifecycle ->
                mutate { current -> current.copy(lifecycle = currentLifecycle) }
            }
        }
    }

    fun currentState(): ProvisioningAggregateState = _state.value

    fun observeState(): StateFlow<ProvisioningAggregateState> = _state.asStateFlow()

    suspend fun seed(): ProvisioningAggregateState {
        val manifest = runtimeBindings.loadModelDistributionManifest()
        return mutate { current ->
            current.copy(
                snapshot = runtimeBindings.currentProvisioningSnapshot(),
                downloads = downloads.value,
                downloadPreferences = runtimeBindings.currentDownloadPreferences(),
                lifecycle = runtimeBindings.currentModelLifecycle(),
                manifest = manifest,
                manifestLoaded = true,
            )
        }
    }

    fun refreshSnapshot(): ProvisioningAggregateState {
        return mutate { current ->
            current.copy(
                snapshot = runtimeBindings.currentProvisioningSnapshot(),
                lifecycle = runtimeBindings.currentModelLifecycle(),
            )
        }
    }

    fun refreshDownloadPreferences(): ProvisioningAggregateState {
        return mutate { current ->
            current.copy(
                downloadPreferences = runtimeBindings.currentDownloadPreferences(),
            )
        }
    }

    fun refreshLifecycle(): ProvisioningAggregateState {
        return mutate { current ->
            current.copy(
                lifecycle = runtimeBindings.currentModelLifecycle(),
            )
        }
    }

    private inline fun mutate(
        block: (ProvisioningAggregateState) -> ProvisioningAggregateState,
    ): ProvisioningAggregateState {
        return _state.updateAndGet(block)
    }
}
