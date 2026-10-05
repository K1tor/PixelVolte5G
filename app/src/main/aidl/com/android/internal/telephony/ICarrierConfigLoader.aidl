package com.android.internal.telephony;

import android.os.PersistableBundle;

/**
 * 与系统 com.android.phone 中 CarrierConfigLoader 对应的 binder 接口。
 * 方法声明顺序必须与 AOSP 一致（决定 binder 事务码），来源：
 * frameworks/base/telephony/java/com/android/internal/telephony/ICarrierConfigLoader.aidl
 * （注解已按 app 可编译性去除，不影响 parcel 编解码）。
 */
interface ICarrierConfigLoader {

    PersistableBundle getConfigForSubId(int subId, String callingPackage);

    PersistableBundle getConfigForSubIdWithFeature(int subId, String callingPackage,
            String callingFeatureId);

    void overrideConfig(int subId, in PersistableBundle overrides, boolean persistent);

    void notifyConfigChangedForSubId(int subId);

    void updateConfigForPhoneId(int phoneId, String simState);

    String getDefaultCarrierServicePackageName();

    PersistableBundle getConfigSubsetForSubIdWithFeature(int subId, String callingPackage,
                String callingFeatureId, in String[] carrierConfigs);
}
