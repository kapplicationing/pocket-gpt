package com.pocketagent.android.voice

import android.app.Activity
import android.os.Bundle
import android.util.Log

/** Release-semantic test hook; compiled only into the isolated voiceProof package. */
class VoiceProofSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = VoiceActivationController(applicationContext).setEnabled(true)
        Log.i(LOG_TAG, "VOICE_PROOF_SETUP|result=${result.name}")
        finish()
    }

    private companion object {
        const val LOG_TAG = "PocketAgentVoiceProof"
    }
}
