package com.pocketagent.android

import android.app.Application
import com.pocketagent.android.runtime.AndroidAppRuntimeHost
import com.pocketagent.android.runtime.AppRuntimeHost
import com.pocketagent.android.runtime.AppRuntimeHostOwner

internal class PocketAgentApplication : Application(), AppRuntimeHostOwner {
    override val appRuntimeHost: AppRuntimeHost by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAppRuntimeHost(this)
    }
}
