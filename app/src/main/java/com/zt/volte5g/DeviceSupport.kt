package com.zt.volte5g

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 机型适配描述：
 * @param device   Build.DEVICE 代号
 * @param name     市场名称
 * @param has5G    是否具备 5G（NR）能力
 * @param hasSa    5G 是否支持独立组网（SA）
 */
private data class PixelModel(val device: String, val name: String, val has5G: Boolean, val hasSa: Boolean)

/**
 * 全系列 Pixel 机型适配矩阵。机制本身与机型无关（取决于系统版本），
 * 这里用于在界面上按机型禁用无意义的功能（如无 5G 调制解调器的机型）。
 */
object DeviceSupport {

    private val models = listOf(
        // Pixel 9 系列（Tensor G4）
        PixelModel("komodo", "Pixel 9 Pro XL", has5G = true, hasSa = true),
        PixelModel("caiman", "Pixel 9 Pro", has5G = true, hasSa = true),
        PixelModel("tokay", "Pixel 9", has5G = true, hasSa = true),
        PixelModel("tegu", "Pixel 9a", has5G = true, hasSa = true),
        PixelModel("comet", "Pixel 9 Pro Fold", has5G = true, hasSa = true),
        // Pixel 8 系列（Tensor G3）
        PixelModel("husky", "Pixel 8 Pro", has5G = true, hasSa = true),
        PixelModel("shiba", "Pixel 8", has5G = true, hasSa = true),
        PixelModel("akita", "Pixel 8a", has5G = true, hasSa = true),
        // Pixel 7 系列（Tensor G2）
        PixelModel("cheetah", "Pixel 7 Pro", has5G = true, hasSa = true),
        PixelModel("panther", "Pixel 7", has5G = true, hasSa = true),
        PixelModel("lynx", "Pixel 7a", has5G = true, hasSa = true),
        PixelModel("felix", "Pixel Fold", has5G = true, hasSa = true),
        // Pixel 6 系列（Tensor G1）
        PixelModel("raven", "Pixel 6 Pro", has5G = true, hasSa = true),
        PixelModel("oriole", "Pixel 6", has5G = true, hasSa = true),
        PixelModel("bluejay", "Pixel 6a", has5G = true, hasSa = true),
        // Pixel 5 系列（骁龙 765G，5G 不支持 SA 语音）
        PixelModel("barbet", "Pixel 5a", has5G = true, hasSa = false),
        PixelModel("redfin", "Pixel 5", has5G = true, hasSa = false),
        PixelModel("bramble", "Pixel 4a (5G)", has5G = true, hasSa = false),
        // 无 5G 机型
        PixelModel("sunfish", "Pixel 4a", has5G = false, hasSa = false),
        PixelModel("flame", "Pixel 4", has5G = false, hasSa = false),
        PixelModel("coral", "Pixel 4 XL", has5G = false, hasSa = false),
        PixelModel("sargo", "Pixel 3a", has5G = false, hasSa = false),
        PixelModel("bonito", "Pixel 3a XL", has5G = false, hasSa = false),
        PixelModel("blueline", "Pixel 3", has5G = false, hasSa = false),
        PixelModel("crosshatch", "Pixel 3 XL", has5G = false, hasSa = false),
    )

    /**
     * 检测当前设备。非 Pixel 机型按“通用 Android 设备”处理：
     * 机制未经验证，但只要系统版本满足要求仍然允许尝试。
     */
    fun detect(context: Context): DeviceProfile {
        val telephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val device = Build.DEVICE
        val known = models.find { it.device == device }
        return if (known != null) {
            DeviceProfile(
                device = known.device,
                name = known.name,
                isPixel = true,
                telephony = telephony,
                has5G = known.has5G && telephony,
                hasSa = known.hasSa && telephony,
            )
        } else {
            DeviceProfile(
                device = device,
                name = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                isPixel = false,
                telephony = telephony,
                has5G = telephony,
                hasSa = telephony,
            )
        }
    }
}

data class DeviceProfile(
    val device: String,
    val name: String,
    val isPixel: Boolean,
    val telephony: Boolean,
    val has5G: Boolean,
    val hasSa: Boolean,
) {
    /** VoNR 仅在具备 5G 能力的机型上有意义 */
    val hasVoNR: Boolean get() = has5G
}
