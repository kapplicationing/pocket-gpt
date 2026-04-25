package com.pocketagent.android.runtime

import android.content.Context

internal interface AppRuntimeHost {
    val foregroundRuntimeServices: AppForegroundRuntimeServices
}

internal interface AppRuntimeHostOwner {
    val appRuntimeHost: AppRuntimeHost
}

internal class AndroidAppRuntimeHost(
    context: Context,
) : AppRuntimeHost {
    private val appContext = context.applicationContext

    override val foregroundRuntimeServices: AppForegroundRuntimeServices by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultAppForegroundRuntimeServices(
            context = appContext,
        )
    }
}

internal fun resolveAppForegroundRuntimeServices(context: Context): AppForegroundRuntimeServices {
    return resolveAppRuntimeHost(context).foregroundRuntimeServices
}

private fun resolveAppRuntimeHost(context: Context): AppRuntimeHost {
    val appContext = context.applicationContext
    val owner = appContext as? AppRuntimeHostOwner
    return checkNotNull(owner) {
        "Application context must implement AppRuntimeHostOwner: ${appContext::class.java.name}"
    }.appRuntimeHost
}
