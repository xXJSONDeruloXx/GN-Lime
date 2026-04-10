package app.gamenative.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.MainActivity
import timber.log.Timber

object IconSwitcher {

    fun applyLauncherIcon(context: Context, useAltIcon: Boolean) {
        val packageManager = context.packageManager

        val defaultAlias = resolveAliasComponent(context, "MainActivityAliasDefault")
        val altAlias = resolveAliasComponent(context, "MainActivityAliasAlt")

        if (defaultAlias == null || altAlias == null) {
            Timber.w(
                "[IconSwitcher] Missing launcher aliases. default=%s alt=%s applicationId=%s",
                defaultAlias,
                altAlias,
                BuildConfig.APPLICATION_ID,
            )
            return
        }

        val defaultState = if (useAltIcon)
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        val altState = if (useAltIcon)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        packageManager.setComponentEnabledSetting(
            defaultAlias,
            defaultState,
            PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            altAlias,
            altState,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun resolveAliasComponent(context: Context, simpleName: String): ComponentName? {
        val packageManager = context.packageManager
        val installedPackage = context.packageName
        val candidates = linkedSetOf(
            "${MainActivity::class.java.packageName}.$simpleName",
            "${BuildConfig.APPLICATION_ID}.$simpleName",
        )

        for (className in candidates) {
            val component = ComponentName(installedPackage, className)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getActivityInfo(
                        component,
                        PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
                }
                return component
            } catch (_: PackageManager.NameNotFoundException) {
                // Try next candidate.
            }
        }

        return null
    }
}


