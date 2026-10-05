package com.zt.volte5g

import java.lang.reflect.Method
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 隐藏 API 辅助。
 *
 * 本项目大量调用 Android 隐藏 API（CarrierConfigManager.overrideConfig、
 * IActivityManager / ITelephony 桩等），需要先解除 hidden-api 限制：
 * HiddenApiBypass.setHiddenApiExemptions("") 会豁免全部签名前缀。
 */
object Hidden {

    /** 解除全部隐藏 API 限制（幂等）。必须在任何隐藏 API 调用之前执行。 */
    @JvmStatic
    fun exemptAll() {
        runCatching { HiddenApiBypass.setHiddenApiExemptions("") }
    }

    /** 反射调用隐藏方法；target 为实例时调实例方法，为 Class 时调静态方法。 */
    fun call(target: Any, methodName: String, parameterTypes: Array<Class<*>>, args: Array<Any?>): Any? {
        val clazz: Class<*> = if (target is Class<*>) target else target.javaClass
        val method: Method = HiddenApiBypass.getDeclaredMethod(clazz, methodName, *parameterTypes)
        runCatching { method.isAccessible = true }
        return method.invoke(target, *args)
    }

    /**
     * 运行时读取设备的隐藏静态 int 常量。
     * 不同 Android 版本常量值可能不同（如 INSTR_FLAG_* 位定义），不能写死。
     */
    fun staticInt(className: String, fieldName: String, fallback: Int): Int = runCatching {
        val field = Class.forName(className).getDeclaredField(fieldName)
        field.isAccessible = true
        field.getInt(null)
    }.getOrDefault(fallback)
}
