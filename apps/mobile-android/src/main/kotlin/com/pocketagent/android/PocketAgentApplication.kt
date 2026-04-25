package com.pocketagent.android

import android.app.Application
import com.pocketagent.android.runtime.AndroidAppRuntimeHost
import com.pocketagent.android.runtime.AppRuntimeHost
import com.pocketagent.android.runtime.AppRuntimeHostOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class PocketAgentApplication : Application(), AppRuntimeHostOwner {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val appRuntimeHost: AppRuntimeHost by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAppRuntimeHost(this)
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch(Dispatchers.IO) {
            AppRuntimeDependencies.installProductionRuntime(this@PocketAgentApplication)
        }
    }
}
