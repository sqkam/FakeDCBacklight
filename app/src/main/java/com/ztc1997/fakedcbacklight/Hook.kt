package com.ztc1997.fakedcbacklight

import android.content.Context
import android.provider.Settings
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

const val HAL_SCREEN_BRIGHTNESS = "COM_ZTC1997_FAKEDCBACKLIGHT_HAL_SCREEN_BRIGHTNESS"
const val REDUCE_BRIGHT_LEVEL = "COM_ZTC1997_FAKEDCBACKLIGHT_REDUCE_BRIGHT_LEVEL"

class Hook : IXposedHookLoadPackage {
    private val prefs = XSharedPreferences(BuildConfig.APPLICATION_ID, "config")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return

        val localDisplayDevice = XposedHelpers.findClass(
            "com.android.server.display.LocalDisplayAdapter\$LocalDisplayDevice",
            lpparam.classLoader
        )
        XposedBridge.hookAllMethods(localDisplayDevice,
            "requestDisplayStateLocked",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val ctx = getOverlayContext(param.thisObject) ?: return
                        val targetBright = param.args.getOrNull(1) as? Float ?: return
                        val targetSdrBright = param.args.getOrNull(2) as? Float
                        val requestedBright = listOfNotNull(targetBright, targetSdrBright)
                            .filter { it >= 0f }
                            .minOrNull() ?: targetBright

                        val enable = getBoolean("pref_enable", true)
                        val preEnable =
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "preEnable")
                        XposedHelpers.setAdditionalInstanceField(param.thisObject, "preEnable", enable)
                        if (!enable) {
                            if (preEnable is Boolean && preEnable)
                                disableReduceBrightColors(ctx)
                            return
                        }

                        val minScreenBright = getFloat("pref_min_screen_bright", 1f)
                        if (requestedBright >= minScreenBright ||
                            (requestedBright < 0 &&
                                getBoolean("pref_disable_on_screenoff", false))
                        ) {
                            disableReduceBrightColors(ctx)
                        } else if (requestedBright >= 0) {
                            val dim = (1 - (requestedBright / minScreenBright)) * getInt(
                                "pref_max_dim_strength",
                                90
                            )
                            updateReduceBrightColors(ctx, dim.toInt())
                            param.args[1] = maxOf(targetBright, minScreenBright)
                            if (targetSdrBright != null) {
                                param.args[2] = maxOf(targetSdrBright, minScreenBright)
                            }
                        }
                    }.onFailure(XposedBridge::log)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val ctx = getOverlayContext(param.thisObject) ?: return
                        val targetBright = param.args.getOrNull(1) as? Float ?: return
                        Settings.System.putFloat(
                            ctx.contentResolver,
                            HAL_SCREEN_BRIGHTNESS,
                            targetBright
                        )
                        val level = Settings.Secure.getInt(
                            ctx.contentResolver,
                            "reduce_bright_colors_level",
                            0
                        )
                        Settings.System.putInt(
                            ctx.contentResolver,
                            REDUCE_BRIGHT_LEVEL,
                            level
                        )
                    }.onFailure(XposedBridge::log)
                }
            })

        val displayPowerController = XposedHelpers.findClass(
            "com.android.server.display.DisplayPowerController",
            lpparam.classLoader
        )

        XposedBridge.hookAllMethods(displayPowerController,
            "handleRbcChanged", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val enable = getBoolean("pref_enable", true)
                    if (enable) {
                        val colorDisplayService = XposedHelpers.getObjectField(param.thisObject, "mCdsi")
                        val isActivated = XposedHelpers.callMethod(
                            colorDisplayService,
                            "isReduceBrightColorsActivated"
                        ) as? Boolean ?: false
                        XposedHelpers.setBooleanField(param.thisObject, "mIsRbcActive", isActivated)
                        param.result = null
                    }
                }
            })
    }

    fun getBoolean(key: String, defValue: Boolean): Boolean {
        if (prefs.hasFileChanged()) {
            prefs.reload()
        }
        return prefs.getBoolean(key, defValue)
    }

    fun getFloat(key: String, defValue: Float): Float {
        if (prefs.hasFileChanged()) {
            prefs.reload()
        }
        return prefs.getFloat(key, defValue)
    }

    fun getInt(key: String, defValue: Int): Int {
        if (prefs.hasFileChanged()) {
            prefs.reload()
        }
        return prefs.getInt(key, defValue)
    }

    private fun getOverlayContext(displayDevice: Any): Context? {
        val localDisplayAdapter = XposedHelpers.getSurroundingThis(displayDevice)
        return XposedHelpers.callMethod(
            localDisplayAdapter,
            "getOverlayContext"
        ) as? Context
    }

    private fun updateReduceBrightColors(ctx: Context, level: Int) {
        Settings.Secure.putInt(
            ctx.contentResolver,
            "reduce_bright_colors_level",
            level
        )
        Settings.Secure.putInt(
            ctx.contentResolver,
            "reduce_bright_colors_activated",
            1
        )
    }

    private fun disableReduceBrightColors(ctx: Context) {
        Settings.Secure.putInt(
            ctx.contentResolver,
            "reduce_bright_colors_level",
            0
        )
        Settings.Secure.putInt(
            ctx.contentResolver,
            "reduce_bright_colors_activated",
            0
        )
    }
}