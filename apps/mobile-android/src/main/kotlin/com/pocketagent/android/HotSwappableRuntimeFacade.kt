package com.pocketagent.android

import android.util.Log
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.ImageFailure
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import com.pocketagent.runtime.RuntimeResourceControl
import com.pocketagent.runtime.RuntimeWarmupSupport
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.ToolFailure
import com.pocketagent.runtime.WarmupResult
import com.pocketagent.runtime.toLegacyString
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleEvent
import com.pocketagent.nativebridge.RuntimeCloseResult
import com.pocketagent.nativebridge.RuntimeLifetimePort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class HotSwappableRuntimeFacade(
    initial: MvpRuntimeFacade,
    private val replacementDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MvpRuntimeFacade, RuntimeWarmupSupport, RuntimeResourceControl, RuntimeLifetimePort {
    private val stateLock = ReentrantLock()
    private val callsDrained = stateLock.newCondition()
    private val lifetimeOwnerLock = ReentrantLock()
    private var delegateState: DelegateState = DelegateState.Ready(initial)
    private var activeDelegateCalls: Int = 0
    private var replacementCounter: Long = 0L
    @Volatile private var desiredRoutingMode: RoutingMode? = null
    @Volatile private var lastKnownLoadedModel: RuntimeLoadedModel? = null
    @Volatile private var lastKnownActiveGenerationCount: Int = 0
    @Volatile private var lastKnownLifecycleEvent: ModelLifecycleEvent? = null
    @Volatile private var lastAppForeground: Boolean? = null
    private val autoReleaseDisableReasons = linkedSetOf<String>()
    private val pendingDelegateActions = ArrayDeque<(MvpRuntimeFacade) -> Unit>()

    /**
     * Replaces the runtime on an explicit background dispatcher. Once replacement starts, new
     * facade calls fail fast while calls that already own a delegate lease drain before close.
     */
    suspend fun replace(newDelegate: MvpRuntimeFacade): RuntimeReplacementResult {
        return withContext(replacementDispatcher) {
            lifetimeOwnerLock.withLock {
                replaceOwned(newDelegate)
            }
        }
    }

    internal fun replaceFromBackground(newDelegate: MvpRuntimeFacade): RuntimeReplacementResult {
        return runBlocking { replace(newDelegate) }
    }

    internal fun availability(): RuntimeFacadeAvailability {
        return stateLock.withLock {
            when (val current = delegateState) {
                is DelegateState.Ready -> RuntimeFacadeAvailability.READY
                is DelegateState.Transition -> current.availability
                is DelegateState.Closed -> RuntimeFacadeAvailability.CLOSED
            }
        }
    }

    private fun replaceOwned(newDelegate: MvpRuntimeFacade): RuntimeReplacementResult {
        val newLifetime = newDelegate as? RuntimeLifetimePort
            ?: return RuntimeReplacementResult.rejected("NEW_RUNTIME_LIFETIME_UNSUPPORTED")
        val transition = beginTransition(RuntimeFacadeAvailability.REPLACING)
        val oldDelegate = transition.delegate
        val oldLifetime = oldDelegate as? RuntimeLifetimePort
        if (oldLifetime == null) {
            runCatching { newLifetime.closeRuntime(REPLACEMENT_CLOSE_TIMEOUT_MS) }
            finishTransition(DelegateState.Ready(oldDelegate))
            return RuntimeReplacementResult.rejected("OLD_RUNTIME_LIFETIME_UNSUPPORTED")
        }
        val previousRoutingMode = desiredRoutingMode
            ?: runCatching { oldDelegate.getRoutingMode() }.getOrDefault(RoutingMode.AUTO)
        val oldClose = closeOldRuntime(transition = transition, oldLifetime = oldLifetime)
        if (!oldClose.success && oldClose.runtimeReusable) {
            runCatching { newLifetime.closeRuntime(REPLACEMENT_CLOSE_TIMEOUT_MS) }
            finishTransition(DelegateState.Ready(oldDelegate))
            return RuntimeReplacementResult.rejected(
                code = oldClose.code ?: "OLD_RUNTIME_CLOSE_FAILED",
                detail = oldClose.detail,
            )
        }
        runCatching { newDelegate.setRoutingMode(previousRoutingMode) }
        desiredRoutingMode = previousRoutingMode
        val resourceControl = newDelegate as? RuntimeResourceControl
        lastKnownLoadedModel = runCatching { resourceControl?.loadedModel() }.getOrNull()
        lastKnownActiveGenerationCount = runCatching { resourceControl?.activeGenerationCount() }
            .getOrNull()
            ?: 0
        stateLock.withLock { autoReleaseDisableReasons.toList() }.forEach { reason ->
            runCatching { resourceControl?.addAutoReleaseDisableReason(reason) }
        }
        when (lastAppForeground) {
            true -> runCatching { resourceControl?.onAppForeground() }
            false -> runCatching { resourceControl?.onAppBackground() }
            null -> Unit
        }
        lastKnownLifecycleEvent = runCatching {
            resourceControl?.currentModelLifecycleEvent()
                ?: newDelegate.currentModelLifecycleEvent()
        }.getOrNull()
        finishTransition(DelegateState.Ready(newDelegate))
        replacementCounter += 1L
        safeLogInfo("RUNTIME_SWAP|phase=replaced|counter=$replacementCounter|routingMode=$previousRoutingMode")
        return RuntimeReplacementResult.replaced(
            code = oldClose.code,
            detail = oldClose.detail,
        )
    }

    private fun closeOldRuntime(
        transition: TransitionStart,
        oldLifetime: RuntimeLifetimePort,
    ): RuntimeCloseResult {
        if (transition.wasClosed) {
            return RuntimeCloseResult.closed()
        }
        return runCatching { oldLifetime.closeRuntime(REPLACEMENT_CLOSE_TIMEOUT_MS) }
            .getOrElse { error ->
                RuntimeCloseResult.terminated(
                    code = "OLD_RUNTIME_CLOSE_EXCEPTION",
                    detail = error.message ?: error::class.simpleName,
                )
            }
    }

    private fun beginTransition(availability: RuntimeFacadeAvailability): TransitionStart {
        return stateLock.withLock {
            val selected = when (val current = delegateState) {
                is DelegateState.Ready -> TransitionStart(current.delegate, wasClosed = false)
                is DelegateState.Closed -> TransitionStart(current.delegate, wasClosed = true)
                is DelegateState.Transition -> error("runtime lifetime mutation already owns the facade")
            }
            delegateState = DelegateState.Transition(
                delegate = selected.delegate,
                availability = availability,
            )
            while (activeDelegateCalls > 0) {
                callsDrained.awaitUninterruptibly()
            }
            selected
        }
    }

    private fun finishTransition(next: DelegateState) {
        if (next is DelegateState.Ready) {
            publishReadyDelegate(next.delegate)
            return
        }
        stateLock.withLock {
            delegateState = next
            pendingDelegateActions.clear()
            callsDrained.signalAll()
        }
    }

    private fun publishReadyDelegate(delegate: MvpRuntimeFacade) {
        while (true) {
            val pending = stateLock.withLock {
                if (pendingDelegateActions.isEmpty()) {
                    delegateState = DelegateState.Ready(delegate)
                    callsDrained.signalAll()
                    return
                }
                buildList {
                    while (pendingDelegateActions.isNotEmpty()) {
                        add(pendingDelegateActions.removeFirst())
                    }
                }
            }
            pending.forEach { action ->
                runCatching { action(delegate) }.onFailure { error ->
                    safeLogWarning(
                        "RUNTIME_SWAP|phase=pending_action_failed|detail=" +
                            (error.message ?: error::class.simpleName),
                    )
                }
            }
        }
    }

    private fun runOrQueue(action: (MvpRuntimeFacade) -> Unit): Boolean {
        val selected = stateLock.withLock {
            when (val current = delegateState) {
                is DelegateState.Ready -> {
                    activeDelegateCalls += 1
                    current.delegate
                }
                is DelegateState.Transition -> {
                    pendingDelegateActions.addLast(action)
                    return true
                }
                is DelegateState.Closed -> return false
            }
        }
        return try {
            action(selected)
            true
        } finally {
            releaseDelegate()
        }
    }

    override fun createSession(): SessionId = withDelegate { it.createSession() }

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> {
        return flow {
            var unavailable: RuntimeFacadeAvailability? = null
            val selected = withDelegate(
                onUnavailable = { state ->
                    unavailable = state
                    null
                },
                block = { delegate -> delegate.streamChat(request) },
            )
            if (selected == null) {
                val state = requireNotNull(unavailable)
                emit(
                    ChatStreamEvent.Failed(
                        requestId = request.requestId,
                        errorCode = state.errorCode(),
                        message = state.userMessage(),
                    ),
                )
                return@flow
            }
            // The lease covers cold-flow creation. RuntimeLifetimePort owns cancellation/drain
            // after collection begins; retaining the lease would prevent close from cancelling it.
            emitAll(selected)
        }
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean {
        return withDelegate(onUnavailable = { false }) { it.cancelGeneration(sessionId) }
    }

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        return withDelegate(onUnavailable = { false }) { it.cancelGenerationByRequest(requestId) }
    }

    override fun runTool(toolName: String, jsonArgs: String): String {
        return withDelegate(
            onUnavailable = { state -> state.toolFailure().toLegacyString() },
        ) { it.runTool(toolName = toolName, jsonArgs = jsonArgs) }
    }

    override fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        return withDelegate(
            onUnavailable = { state -> state.toolFailure() },
        ) { it.runToolDetailed(toolName = toolName, jsonArgs = jsonArgs) }
    }

    override fun analyzeImage(imagePath: String, prompt: String): String {
        return withDelegate(
            onUnavailable = { state -> state.imageFailure().toLegacyString() },
        ) { it.analyzeImage(imagePath = imagePath, prompt = prompt) }
    }

    override fun analyzeImageDetailed(imagePath: String, prompt: String): ImageAnalysisResult {
        return withDelegate(
            onUnavailable = { state -> state.imageFailure() },
        ) { it.analyzeImageDetailed(imagePath = imagePath, prompt = prompt) }
    }

    override fun exportDiagnostics(): String {
        return withDelegate(
            onUnavailable = { state -> "RUNTIME_FACADE|availability=${state.name.lowercase()}" },
        ) { it.exportDiagnostics() }
    }

    override fun setRoutingMode(mode: RoutingMode) {
        desiredRoutingMode = mode
        runOrQueue { delegate ->
            desiredRoutingMode = mode
            delegate.setRoutingMode(mode)
        }
    }

    override fun getRoutingMode(): RoutingMode {
        return withDelegate(
            onUnavailable = { desiredRoutingMode ?: RoutingMode.AUTO },
        ) { delegate -> delegate.getRoutingMode().also { desiredRoutingMode = it } }
    }

    override fun runStartupChecks(): List<String> {
        return withDelegate(onUnavailable = { state -> listOf(state.errorCode()) }) {
            it.runStartupChecks()
        }
    }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        runOrQueue { it.restoreSession(sessionId, turns) }
    }

    override fun deleteSession(sessionId: SessionId): Boolean {
        var deleted = true
        return runOrQueue { delegate -> deleted = delegate.deleteSession(sessionId) } && deleted
    }

    override fun runtimeBackend(): String? = withDelegate(onUnavailable = { null }) { it.runtimeBackend() }

    override fun supportsGpuOffload(): Boolean = withDelegate(onUnavailable = { false }) {
        it.supportsGpuOffload()
    }

    override fun warmupActiveModel(): WarmupResult {
        return withDelegate(
            onUnavailable = { state -> WarmupResult.skipped(state.errorCode()) },
        ) { facade ->
            (facade as? RuntimeWarmupSupport)?.warmupActiveModel()
                ?: WarmupResult.skipped("warmup_unsupported")
        }
    }

    override fun evictResidentModel(reason: String): Boolean {
        return withDelegate(onUnavailable = { false }) { facade ->
            (facade as? RuntimeResourceControl)?.evictResidentModel(reason) ?: false
        }
    }

    override fun loadModel(modelId: String, modelVersion: String?): RuntimeModelLifecycleCommandResult {
        return withDelegate(
            onUnavailable = { state -> state.lifecycleRejection() },
        ) { facade ->
            (facade as? RuntimeResourceControl)?.loadModel(modelId = modelId, modelVersion = modelVersion)
                ?: RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.UNKNOWN,
                    detail = "runtime_model_load_unsupported",
                )
        }
    }

    override fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return withDelegate(
            onUnavailable = { state -> state.lifecycleRejection() },
        ) { facade ->
            (facade as? RuntimeResourceControl)?.offloadModel(reason = reason)
                ?: RuntimeModelLifecycleCommandResult.applied()
        }
    }

    override fun loadedModel(): RuntimeLoadedModel? {
        return withDelegate(onUnavailable = { lastKnownLoadedModel }) { facade ->
            (facade as? RuntimeResourceControl)?.loadedModel().also { loaded ->
                lastKnownLoadedModel = loaded
            }
        }
    }

    override fun activeGenerationCount(): Int {
        return withDelegate(
            onUnavailable = { maxOf(1, lastKnownActiveGenerationCount) },
        ) { facade ->
            ((facade as? RuntimeResourceControl)?.activeGenerationCount() ?: 0).also { count ->
                lastKnownActiveGenerationCount = count
            }
        }
    }

    override fun touchKeepAlive(): Boolean {
        return withDelegate(onUnavailable = { false }) { facade ->
            (facade as? RuntimeResourceControl)?.touchKeepAlive() ?: false
        }
    }

    override fun shortenKeepAlive(ttlMs: Long): Boolean {
        return withDelegate(onUnavailable = { false }) { facade ->
            (facade as? RuntimeResourceControl)?.shortenKeepAlive(ttlMs) ?: false
        }
    }

    override fun onTrimMemory(level: Int): Boolean {
        var handled = true
        return runOrQueue { facade ->
            handled = (facade as? RuntimeResourceControl)?.onTrimMemory(level) ?: false
        } && handled
    }

    override fun onAppBackground(): Boolean {
        lastAppForeground = false
        var handled = true
        return runOrQueue { facade ->
            handled = (facade as? RuntimeResourceControl)?.onAppBackground() ?: false
        } && handled
    }

    override fun onAppForeground(): Boolean {
        lastAppForeground = true
        var handled = true
        return runOrQueue { facade ->
            handled = (facade as? RuntimeResourceControl)?.onAppForeground() ?: false
        } && handled
    }

    override fun addAutoReleaseDisableReason(reason: String) {
        stateLock.withLock { autoReleaseDisableReasons += reason }
        runOrQueue { facade ->
            (facade as? RuntimeResourceControl)?.addAutoReleaseDisableReason(reason)
        }
    }

    override fun removeAutoReleaseDisableReason(reason: String) {
        stateLock.withLock { autoReleaseDisableReasons -= reason }
        runOrQueue { facade ->
            (facade as? RuntimeResourceControl)?.removeAutoReleaseDisableReason(reason)
        }
    }

    override fun exportDiagnosticsJson(): String? {
        return withDelegate(onUnavailable = { null }) { facade ->
            (facade as? RuntimeResourceControl)?.exportDiagnosticsJson()
                ?: facade.exportDiagnosticsJson()
        }
    }

    override fun currentModelLifecycleEvent(): ModelLifecycleEvent? {
        return withDelegate(onUnavailable = { lastKnownLifecycleEvent }) { facade ->
            (
                (facade as? RuntimeResourceControl)?.currentModelLifecycleEvent()
                    ?: facade.currentModelLifecycleEvent()
                ).also { event -> lastKnownLifecycleEvent = event }
        }
    }

    override fun observeModelLifecycleEvents(listener: (ModelLifecycleEvent) -> Unit): AutoCloseable {
        return withDelegate(onUnavailable = { AutoCloseable { } }) { facade ->
            val cachingListener: (ModelLifecycleEvent) -> Unit = { event ->
                lastKnownLifecycleEvent = event
                listener(event)
            }
            (facade as? RuntimeResourceControl)?.observeModelLifecycleEvents(cachingListener)
                ?: facade.observeModelLifecycleEvents(cachingListener)
        }
    }

    override fun closeRuntime(timeoutMs: Long): RuntimeCloseResult {
        if (!lifetimeOwnerLock.tryLock()) {
            return RuntimeCloseResult.rejected("RUNTIME_LIFETIME_MUTATION_IN_PROGRESS")
        }
        return try {
            if (availability() == RuntimeFacadeAvailability.CLOSED) {
                return RuntimeCloseResult.closed()
            }
            val transition = beginTransition(RuntimeFacadeAvailability.CLOSING)
            val lifetime = transition.delegate as? RuntimeLifetimePort
            if (lifetime == null) {
                finishTransition(DelegateState.Ready(transition.delegate))
                return RuntimeCloseResult.rejected("RUNTIME_LIFETIME_UNSUPPORTED")
            }
            val result = runCatching { lifetime.closeRuntime(timeoutMs) }.getOrElse { error ->
                RuntimeCloseResult.terminated(
                    code = "RUNTIME_CLOSE_EXCEPTION",
                    detail = error.message ?: error::class.simpleName,
                )
            }
            val next = if (!result.success && result.runtimeReusable) {
                DelegateState.Ready(transition.delegate)
            } else {
                DelegateState.Closed(transition.delegate)
            }
            finishTransition(next)
            result
        } finally {
            lifetimeOwnerLock.unlock()
        }
    }

    private inline fun <T> withDelegate(block: (MvpRuntimeFacade) -> T): T {
        return withDelegate(
            onUnavailable = { unavailable -> throw RuntimeFacadeUnavailableException(unavailable) },
            block = block,
        )
    }

    private inline fun <T> withDelegate(
        onUnavailable: (RuntimeFacadeAvailability) -> T,
        block: (MvpRuntimeFacade) -> T,
    ): T {
        return when (val selection = acquireDelegate()) {
            is DelegateSelection.Unavailable -> onUnavailable(selection.availability)
            is DelegateSelection.Acquired -> {
                try {
                    block(selection.delegate)
                } finally {
                    releaseDelegate()
                }
            }
        }
    }

    private fun acquireDelegate(): DelegateSelection {
        return stateLock.withLock {
            when (val current = delegateState) {
                is DelegateState.Ready -> {
                    activeDelegateCalls += 1
                    DelegateSelection.Acquired(current.delegate)
                }
                is DelegateState.Transition -> DelegateSelection.Unavailable(current.availability)
                is DelegateState.Closed -> DelegateSelection.Unavailable(RuntimeFacadeAvailability.CLOSED)
            }
        }
    }

    private fun releaseDelegate() {
        stateLock.withLock {
            activeDelegateCalls -= 1
            check(activeDelegateCalls >= 0) { "runtime delegate call lease underflow" }
            if (activeDelegateCalls == 0) {
                callsDrained.signalAll()
            }
        }
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i("AppRuntimeDeps", message) }
    }

    private fun safeLogWarning(message: String) {
        runCatching { Log.w("AppRuntimeDeps", message) }
    }

    private sealed interface DelegateState {
        data class Ready(val delegate: MvpRuntimeFacade) : DelegateState
        data class Transition(
            val delegate: MvpRuntimeFacade,
            val availability: RuntimeFacadeAvailability,
        ) : DelegateState
        data class Closed(val delegate: MvpRuntimeFacade) : DelegateState
    }

    private sealed interface DelegateSelection {
        data class Acquired(val delegate: MvpRuntimeFacade) : DelegateSelection
        data class Unavailable(val availability: RuntimeFacadeAvailability) : DelegateSelection
    }

    private data class TransitionStart(
        val delegate: MvpRuntimeFacade,
        val wasClosed: Boolean,
    )

    private companion object {
        const val REPLACEMENT_CLOSE_TIMEOUT_MS = 5_000L
    }
}

internal enum class RuntimeFacadeAvailability {
    READY,
    REPLACING,
    CLOSING,
    CLOSED,
}

internal class RuntimeFacadeUnavailableException(
    val availability: RuntimeFacadeAvailability,
) : IllegalStateException(
    when (availability) {
        RuntimeFacadeAvailability.READY -> "RUNTIME_READY"
        RuntimeFacadeAvailability.REPLACING -> "RUNTIME_REPLACEMENT_IN_PROGRESS"
        RuntimeFacadeAvailability.CLOSING -> "RUNTIME_CLOSE_IN_PROGRESS"
        RuntimeFacadeAvailability.CLOSED -> "RUNTIME_CLOSED"
    },
)

private fun RuntimeFacadeAvailability.errorCode(): String {
    return when (this) {
        RuntimeFacadeAvailability.READY -> "RUNTIME_READY"
        RuntimeFacadeAvailability.REPLACING -> "RUNTIME_REPLACEMENT_IN_PROGRESS"
        RuntimeFacadeAvailability.CLOSING -> "RUNTIME_CLOSE_IN_PROGRESS"
        RuntimeFacadeAvailability.CLOSED -> "RUNTIME_CLOSED"
    }
}

private fun RuntimeFacadeAvailability.userMessage(): String {
    return when (this) {
        RuntimeFacadeAvailability.READY -> "Runtime is ready."
        RuntimeFacadeAvailability.REPLACING -> "Runtime is switching models. Try again shortly."
        RuntimeFacadeAvailability.CLOSING -> "Runtime is shutting down. Try again shortly."
        RuntimeFacadeAvailability.CLOSED -> "Runtime is unavailable."
    }
}

private fun RuntimeFacadeAvailability.lifecycleRejection(): RuntimeModelLifecycleCommandResult {
    return RuntimeModelLifecycleCommandResult.rejected(
        code = ModelLifecycleErrorCode.BUSY_GENERATION,
        detail = errorCode(),
    )
}

private fun RuntimeFacadeAvailability.toolFailure(): ToolExecutionResult {
    return ToolExecutionResult.Failure(
        ToolFailure.Execution(
            code = errorCode().lowercase(),
            userMessage = userMessage(),
            technicalDetail = name,
        ),
    )
}

private fun RuntimeFacadeAvailability.imageFailure(): ImageAnalysisResult {
    return ImageAnalysisResult.Failure(
        ImageFailure.Runtime(
            code = errorCode().lowercase(),
            userMessage = userMessage(),
            technicalDetail = name,
        ),
    )
}

internal data class RuntimeReplacementResult(
    val success: Boolean,
    val code: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun replaced(code: String? = null, detail: String? = null): RuntimeReplacementResult {
            return RuntimeReplacementResult(success = true, code = code, detail = detail)
        }
        fun rejected(code: String, detail: String? = null): RuntimeReplacementResult {
            return RuntimeReplacementResult(success = false, code = code, detail = detail)
        }
    }
}
