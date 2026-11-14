package mindustrytool.features.background;

import arc.Core;
import arc.util.Nullable;

/**
 * Как вписывать картинку фона в экран. Раньше фон ВСЕГДА растягивался на весь экран - любая картинка не 16:9
 * (и любой экран не 16:9) получала искажённые пропорции. CROP - заполнить экран с сохранением пропорций и обрезкой
 * лишнего по краям, STRETCH - прежнее поведение.
 */
public final class BackgroundScaling {

    public static final String CROP = "crop";
    public static final String STRETCH = "stretch";

    private BackgroundScaling() {
    }

    /** Неизвестное/пустое значение (в т.ч. из чужих или повреждённых настроек) считаем обрезкой, как умолчание. */
    public static boolean isCrop(@Nullable String mode) {
        return !STRETCH.equals(mode);
    }

    public static boolean isCrop() {
        return isCrop(Core.settings.getString(BackgroundFeature.SETTING_SCALING_KEY, CROP));
    }

    public static float drawWidth(float textureWidth, float textureHeight, float screenWidth, float screenHeight,
            boolean crop) {
        return crop ? textureWidth * cropScale(textureWidth, textureHeight, screenWidth, screenHeight) : screenWidth;
    }

    public static float drawHeight(float textureWidth, float textureHeight, float screenWidth, float screenHeight,
            boolean crop) {
        return crop ? textureHeight * cropScale(textureWidth, textureHeight, screenWidth, screenHeight) : screenHeight;
    }

    private static float cropScale(float textureWidth, float textureHeight, float screenWidth, float screenHeight) {
        //битая/пустая текстура не должна давать деление на ноль и NaN-размер прямоугольника
        if (textureWidth <= 0f || textureHeight <= 0f) {
            return 1f;
        }

        return Math.max(screenWidth / textureWidth, screenHeight / textureHeight);
    }
}
