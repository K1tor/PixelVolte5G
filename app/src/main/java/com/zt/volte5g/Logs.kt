package com.zt.volte5g

import android.util.Log

/** 统一日志标签，便于用 `adb logcat -s Volte5G` 抓现场。 */
object Logs {
    private const val TAG = "Volte5G"

    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String) = Log.w(TAG, msg)
    fun e(msg: String) = Log.e(TAG, msg)
    fun d(msg: String) = Log.d(TAG, msg)
}
