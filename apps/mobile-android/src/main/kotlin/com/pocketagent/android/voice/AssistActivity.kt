package com.pocketagent.android.voice

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pocketagent.android.ui.PocketAgentTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A visible, cancellable assistant surface that keeps Android activity launches user-authorized. */
class AssistActivity : ComponentActivity() {
    private var status by mutableStateOf("Preparing local voice…")
    private var detail by mutableStateOf("Listening starts in a moment.")
    private var finished = false
    private var sessionActive = false
    private var permissionInFlight = false
    private var pendingFinal = false
    private var activeSessionId = VoiceSessionSignals.NO_SESSION_ID
    private var finishJob: Job? = null
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionInFlight = false
        status = if (granted) "Camera permission ready" else "Camera permission denied"
        detail = if (granted) {
            "Invoke PocketAgent again to change the flashlight."
        } else {
            "Flashlight control remains unavailable. Other voice features still work."
        }
        if (pendingFinal) {
            pendingFinal = false
            scheduleSuccessfulFinish()
        }
    }

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val eventSessionId = intent?.getLongExtra(
                VoiceSessionSignals.EXTRA_SESSION_ID,
                VoiceSessionSignals.NO_SESSION_ID,
            ) ?: VoiceSessionSignals.NO_SESSION_ID
            if (!VoiceSessionSignals.matches(activeSessionId, eventSessionId)) return
            if (intent?.action == VoiceSessionSignals.ACTION_REQUEST_CAMERA_PERMISSION) {
                permissionInFlight = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                return
            }
            if (intent?.action != VoiceSessionSignals.ACTION_UPDATE) return
            status = intent.getStringExtra(VoiceSessionSignals.EXTRA_STATUS).orEmpty().ifBlank { status }
            detail = intent.getStringExtra(VoiceSessionSignals.EXTRA_DETAIL).orEmpty().ifBlank { detail }
            if (intent.getBooleanExtra(VoiceSessionSignals.EXTRA_FINAL, false) && !finished) {
                sessionActive = false
                if (permissionInFlight) {
                    pendingFinal = true
                } else {
                    scheduleSuccessfulFinish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            status = state.getString(STATE_STATUS, status)
            detail = state.getString(STATE_DETAIL, detail)
            sessionActive = state.getBoolean(STATE_SESSION_ACTIVE)
            finished = state.getBoolean(STATE_FINISHED)
            permissionInFlight = state.getBoolean(STATE_PERMISSION_IN_FLIGHT)
            pendingFinal = state.getBoolean(STATE_PENDING_FINAL)
            activeSessionId = state.getLong(STATE_SESSION_ID, VoiceSessionSignals.NO_SESSION_ID)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        ContextCompat.registerReceiver(
            this,
            sessionReceiver,
            IntentFilter(VoiceSessionSignals.ACTION_UPDATE).apply {
                addAction(VoiceSessionSignals.ACTION_REQUEST_CAMERA_PERMISSION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent {
            PocketAgentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(status, style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(detail, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = ::cancelAndFinish,
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = cancelAndFinish()
            },
        )
        if (savedInstanceState == null) {
            beginCapture(intent)
        } else if (finished) {
            scheduleSuccessfulFinish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        beginCapture(intent)
    }

    override fun onResume() {
        super.onResume()
        VoiceSessionVisibility.setVisible(hasWindowFocus())
    }

    override fun onPause() {
        VoiceSessionVisibility.setVisible(false)
        super.onPause()
    }

    override fun onStop() {
        if (sessionActive && !permissionInFlight && !isChangingConfigurations) {
            sessionActive = false
            if (VoiceSessionVisibility.consumeExternalActivityStarting()) {
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                OffasRuntime.cancelCapture(applicationContext, activeSessionId)
            }
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            VoiceSessionVisibility.setVisible(hasFocus)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_STATUS, status)
        outState.putString(STATE_DETAIL, detail)
        outState.putBoolean(STATE_SESSION_ACTIVE, sessionActive)
        outState.putBoolean(STATE_FINISHED, finished)
        outState.putBoolean(STATE_PERMISSION_IN_FLIGHT, permissionInFlight)
        outState.putBoolean(STATE_PENDING_FINAL, pendingFinal)
        outState.putLong(STATE_SESSION_ID, activeSessionId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessionReceiver) }
        super.onDestroy()
    }

    private fun beginCapture(intent: Intent) {
        finishJob?.cancel()
        finishJob = null
        finished = false
        pendingFinal = false
        sessionActive = true
        VoiceSessionVisibility.beginSession()
        status = "Preparing local voice…"
        detail = "Listening starts in a moment."
        if (intent.getBooleanExtra(
                PocketAgentVoiceInteractionService.EXTRA_ATTACH_TO_RUNNING_CAPTURE,
                false,
            )
        ) {
            activeSessionId = intent.getLongExtra(
                VoiceSessionSignals.EXTRA_SESSION_ID,
                VoiceSessionSignals.NO_SESSION_ID,
            )
            status = "Offas heard you"
            detail = "Listening for your command…"
        } else {
            activeSessionId = VoiceSessionSignals.newSessionId()
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val source = if (keyguardManager.isDeviceLocked) {
                VoiceInvocationSource.LOCKED_ASSISTANT
            } else {
                assistInvocationSource(intent.component?.className)
            }
            OffasRuntime.captureOnce(applicationContext, source, activeSessionId)
        }
    }

    private fun cancelAndFinish() {
        if (sessionActive) {
            sessionActive = false
            OffasRuntime.cancelCapture(applicationContext, activeSessionId)
        }
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun scheduleSuccessfulFinish() {
        finished = true
        sessionActive = false
        setResult(Activity.RESULT_OK)
        finishJob?.cancel()
        finishJob = lifecycleScope.launch {
            delay(1_200)
            finish()
        }
    }

    private companion object {
        const val STATE_STATUS = "voice_status"
        const val STATE_DETAIL = "voice_detail"
        const val STATE_SESSION_ACTIVE = "voice_session_active"
        const val STATE_FINISHED = "voice_finished"
        const val STATE_PERMISSION_IN_FLIGHT = "voice_permission_in_flight"
        const val STATE_PENDING_FINAL = "voice_pending_final"
        const val STATE_SESSION_ID = "voice_session_id"
    }
}

internal const val INTERNAL_ASSIST_ACTIVITY_CLASS =
    "com.pocketagent.android.voice.InternalAssistActivity"

internal fun assistInvocationSource(componentClassName: String?): VoiceInvocationSource {
    return if (componentClassName == INTERNAL_ASSIST_ACTIVITY_CLASS) {
        VoiceInvocationSource.ASSISTANT
    } else {
        VoiceInvocationSource.UNTRUSTED_ASSISTANT
    }
}

internal object VoiceSessionVisibility {
    private val visible = AtomicBoolean(false)
    private val externalActivityStarting = AtomicBoolean(false)

    fun isVisible(): Boolean = visible.get()

    fun setVisible(value: Boolean) {
        visible.set(value)
    }

    fun beginSession() {
        externalActivityStarting.set(false)
    }

    fun markExternalActivityStarting() {
        externalActivityStarting.set(true)
    }

    fun cancelExternalActivityStarting() {
        externalActivityStarting.set(false)
    }

    fun consumeExternalActivityStarting(): Boolean = externalActivityStarting.getAndSet(false)
}

internal object VoiceSessionSignals {
    const val ACTION_UPDATE = "com.pocketagent.android.voice.SESSION_UPDATE"
    const val ACTION_REQUEST_CAMERA_PERMISSION =
        "com.pocketagent.android.voice.REQUEST_CAMERA_PERMISSION"
    const val EXTRA_STATUS = "status"
    const val EXTRA_DETAIL = "detail"
    const val EXTRA_FINAL = "final"
    const val EXTRA_SESSION_ID = "session_id"
    const val NO_SESSION_ID = 0L
    private val sessionSequence = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L))
    private val currentSessionId = AtomicLong(NO_SESSION_ID)

    fun newSessionId(): Long = sessionSequence.incrementAndGet()

    fun setCurrentSessionId(sessionId: Long) {
        currentSessionId.set(sessionId)
    }

    fun currentSessionId(): Long = currentSessionId.get()

    fun matches(activeSessionId: Long, eventSessionId: Long): Boolean {
        return activeSessionId != NO_SESSION_ID && activeSessionId == eventSessionId
    }

    fun publish(
        context: Context,
        status: String,
        detail: String,
        final: Boolean = false,
    ) {
        context.sendBroadcast(
            Intent(ACTION_UPDATE)
                .setPackage(context.packageName)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_DETAIL, detail)
                .putExtra(EXTRA_FINAL, final)
                .putExtra(EXTRA_SESSION_ID, currentSessionId()),
        )
    }

    fun requestCameraPermission(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_REQUEST_CAMERA_PERMISSION)
                .setPackage(context.packageName)
                .putExtra(EXTRA_SESSION_ID, currentSessionId()),
        )
    }
}
