package com.pocketagent.android.runtime

import android.content.Context

internal interface AppRuntimeHost {
    val runtimeAccess: AppRuntimeAccess
    val foregroundRuntimeServices: AppForegroundRuntimeServices
}

internal interface AppRuntimeHostOwner {
    val appRuntimeHost: AppRuntimeHost
}

internal class AndroidAppRuntimeHost(
    context: Context,
    override val runtimeAccess: AppRuntimeAccess = CompatibilityAppRuntimeAccess,
) : AppRuntimeHost {
    private val appContext = context.applicationContext

    override val foregroundRuntimeServices: AppForegroundRuntimeServices by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultAppForegroundRuntimeServices(
            context = appContext,
            runtimeAccess = runtimeAccess,
        )
    }
}

private object CompatibilityAppRuntimeHostRegistry {
    private val lock = Any()
    @Volatile
    private var cachedAppContext: Context? = null
    @Volatile
    private var cachedHost: AppRuntimeHost? = null

    fun hostFor(context: Context): AppRuntimeHost {
        val appContext = context.applicationContext
        val existingHost = cachedHost
        if (existingHost != null && cachedAppContext === appContext) {
            return existingHost
        }
        return synchronized(lock) {
            val synchronizedHost = cachedHost
            if (synchronizedHost != null && cachedAppContext === appContext) {
                synchronizedHost
            } else {
                AndroidAppRuntimeHost(
                    context = appContext,
                    runtimeAccess = CompatibilityAppRuntimeAccess,
                ).also { host ->
                    cachedAppContext = appContext
                    cachedHost = host
                }
            }
        }
    }
}

internal fun resolveAppRuntimeAccess(context: Context): AppRuntimeAccess {
    return resolveAppRuntimeHost(context).runtimeAccess
}

internal fun resolveAppForegroundRuntimeServices(context: Context): AppForegroundRuntimeServices {
    return resolveAppRuntimeHost(context).foregroundRuntimeServices
}

private fun resolveAppRuntimeHost(context: Context): AppRuntimeHost {
    val owner = context.applicationContext as? AppRuntimeHostOwner
    return owner?.appRuntimeHost ?: CompatibilityAppRuntimeHostRegistry.hostFor(context)
}
