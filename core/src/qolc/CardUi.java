package qolc;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import mindustry.ui.Styles;

/**
 * Общие кирпичики для диалогов портированных фич: панель-"карточка" с цветным заголовком (как в
 * оригинале с v7.x, где диалоги ключей/палитры переехали с голой колонки строк на карточки) и
 * ширина/высота диалога, привязанная к экрану.
 * <p>
 * Размеры ячеек Arc умножаются на {@code Scl.scl(1)} (настройка "масштаб интерфейса"), а размер сцены -
 * в пикселях, поэтому доля экрана делится на масштаб - иначе на масштабе выше 100% диалог вылезает за
 * экран (оригинал на Rhino этого не делал, но на 100% разницы нет).
 */
public final class CardUi{
    private static final Color separator = Color.valueOf("3a3a46");

    private CardUi(){
    }

    /** Карточка внутри {@code root}: полупрозрачный фон, заголовок и тонкая линия под ним. Возвращает саму карточку - в неё добавлять содержимое. */
    public static Table card(Table root, String title, String titleColorHex){
        Table card = root.table(Styles.black6).growX().padBottom(8f).get();
        card.margin(8f);
        card.top().left();
        card.add("[#" + titleColorHex + "]" + title + "[]").style(Styles.outlineLabel).fontScale(0.9f).left().padBottom(4f).row();
        card.image().color(separator).height(1.5f).growX().padBottom(6f).row();
        return card;
    }

    /** Ширина диалога в единицах ячейки: {@code fraction} экрана, но не больше {@code max}. */
    public static float width(float fraction, float max){
        return Math.min(Core.scene.getWidth() * fraction / Scl.scl(1f), max);
    }

    /** Высота диалога в единицах ячейки: {@code fraction} экрана, но не больше {@code max}. */
    public static float height(float fraction, float max){
        return Math.min(Core.scene.getHeight() * fraction / Scl.scl(1f), max);
    }
}
