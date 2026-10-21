package com.wavlin.music.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Launcher icons the user can pick from. Every option is an <activity-alias> in the manifest;
 * exactly one alias is enabled at any time, and that is the icon the launcher shows.
 */
enum class AppIcon(val alias: String) {
    DEFAULT("MainActivityAlias"),
    CLASSIC("MainActivityStatic"),
    PINK("MainActivityPink"),
    RED("MainActivityRed"),
    BLACK("MainActivityBlack"),
}

object IconUtils {
    private fun component(context: Context, icon: AppIcon) =
        ComponentName(context, "com.wavlin.music.${icon.alias}")

    private fun isEnabled(context: Context, icon: AppIcon): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(context, icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            // Never touched: fall back to the manifest default (only DEFAULT is enabled there)
            else -> icon == AppIcon.DEFAULT
        }

    /** Reads the real state from the system, so the UI always matches the launcher. */
    fun getCurrentIcon(context: Context): AppIcon =
        AppIcon.values().firstOrNull { isEnabled(context, it) } ?: AppIcon.DEFAULT

    fun setAppIcon(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        // Enable the new alias first so there is never a moment with no launcher entry
        pm.setComponentEnabledSetting(
            component(context, icon),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppIcon.values().filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(
                component(context, it),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /** Kept for compatibility with the old on/off toggle. */
    fun setIcon(context: Context, enabled: Boolean) =
        setAppIcon(context, if (enabled) AppIcon.DEFAULT else AppIcon.CLASSIC)
}
