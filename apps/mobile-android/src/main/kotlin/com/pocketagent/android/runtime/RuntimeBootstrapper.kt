package com.pocketagent.android.runtime

import android.content.Context
import com.pocketagent.runtime.MvpRuntimeFacade

internal interface AppRuntimeBootstrapAccess {
    fun installProductionRuntime(context: Context)
    fun runtimeFacade(context: Context): MvpRuntimeFacade
    fun runtimeTuning(context: Context): AndroidRuntimeTuningStore
}

private object SingletonAppRuntimeBootstrapAccess : AppRuntimeBootstrapAccess {
    override fun installProductionRuntime(context: Context) {
        DefaultAppRuntimeAccess.installProductionRuntime(context.applicationContext)
    }

    override fun runtimeFacade(context: Context): MvpRuntimeFacade {
        return DefaultAppRuntimeAccess.runtimeFacade(context.applicationContext)
    }

    override fun runtimeTuning(context: Context): AndroidRuntimeTuningStore {
        return DefaultAppRuntimeAccess.runtimeTuning(context.applicationContext)
    }
}

object RuntimeBootstrapper {
    @Volatile
    private var access: AppRuntimeBootstrapAccess = SingletonAppRuntimeBootstrapAccess

    fun installProductionRuntime(context: Context) {
        access.installProductionRuntime(context.applicationContext)
    }

    fun runtimeFacade(context: Context): MvpRuntimeFacade {
        return access.runtimeFacade(context.applicationContext)
    }

    fun runtimeTuning(context: Context): AndroidRuntimeTuningStore {
        return access.runtimeTuning(context.applicationContext)
    }

    internal fun swapAccessForTests(testAccess: AppRuntimeBootstrapAccess): AutoCloseable {
        val previous = access
        access = testAccess
        return AutoCloseable {
            access = previous
        }
    }
}
