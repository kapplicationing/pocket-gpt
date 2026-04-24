package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.DownloadPreferencesState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ProvisioningAggregateState(
    val snapshot: RuntimeProvisioningSnapshot = RuntimeProvisioningSnapshot.empty(),
    val downloads: List<DownloadTaskState> = emptyList(),
    val downloadPreferences: DownloadPreferencesState = DownloadPreferencesState(),
    val lifecycle: RuntimeModelLifecycleSnapshot = RuntimeModelLifecycleSnapshot.initial(),
    val manifest: ModelDistributionManifest = ModelDistributionManifest(models = emptyList()),
    val manifestLoaded: Boolean = false,
)

internal class DefaultProvisioningAggregateStore(
    private val dependencies: ProvisioningDependencyAccess,
    coroutineScope: CoroutineScope,
) {
    private val downloads = dependencies.observeDownloads()
    private val downloadPreferences = dependencies.observeDownloadPreferences()
    private val lifecycle = dependencies.observeModelLifecycle()
    private val _state = MutableStateFlow(
        ProvisioningAggregateState(
            snapshot = dependencies.currentProvisioningSnapshot(),
            downloads = downloads.value,
            downloadPreferences = dependencies.currentDownloadPreferences(),
            lifecycle = dependencies.currentModelLifecycle(),
        ),
    )

    init {
        coroutineScope.launch {
            downloads.collect { tasks ->
                mutate { current ->
                    current.copy(
                        snapshot = dependencies.currentProvisioningSnapshot(),
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
        val manifest = dependencies.loadModelDistributionManifest()
        return mutate { current ->
            current.copy(
                snapshot = dependencies.currentProvisioningSnapshot(),
                downloads = downloads.value,
                downloadPreferences = dependencies.currentDownloadPreferences(),
                lifecycle = dependencies.currentModelLifecycle(),
                manifest = manifest,
                manifestLoaded = true,
            )
        }
    }

    fun refreshSnapshot(): ProvisioningAggregateState {
        return mutate { current ->
            current.copy(
                snapshot = dependencies.currentProvisioningSnapshot(),
                lifecycle = dependencies.currentModelLifecycle(),
            )
        }
    }

    fun refreshDownloadPreferences(): ProvisioningAggregateState {
        return mutate { current ->
            current.copy(
                downloadPreferences = dependencies.currentDownloadPreferences(),
            )
        }
    }

    fun refreshLifecycle(): ProvisioningAggregateState {
        return mutate { current ->
            current.copy(
                lifecycle = dependencies.currentModelLifecycle(),
            )
        }
    }

    private inline fun mutate(
        block: (ProvisioningAggregateState) -> ProvisioningAggregateState,
    ): ProvisioningAggregateState {
        var nextState: ProvisioningAggregateState? = null
        _state.update { current ->
            block(current).also { updated ->
                nextState = updated
            }
        }
        return checkNotNull(nextState)
    }
}

internal class AggregateProjectionStateFlow<T>(
    private val source: StateFlow<ProvisioningAggregateState>,
    private val project: (ProvisioningAggregateState) -> T,
) : StateFlow<T> {
    override val replayCache: List<T>
        get() = listOf(value)

    override val value: T
        get() = project(source.value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        source.collect { aggregate ->
            collector.emit(project(aggregate))
        }
    }
}
