package scheme.ui;

import arc.scene.ui.Slider;
import arc.util.Strings;

/**
 * Ввод числа с клавиатуры для слайдера: разбирает текст в единицах отображения и записывает
 * значение обратно без округления до шага слайдера (иначе 1000001 превращалось бы в 1000000).
 */
public class AmountInput{

    /**
     * @param scale единиц слайдера в одной отображаемой единице, например 60 для тиков, показанных секундами
     * @param floor результат не меньше этого значения, в единицах слайдера
     * @return значение в единицах слайдера или NaN, если текст - не конечное число
     */
    public static float parse(String text, float scale, float floor){
        if(text == null) return Float.NaN;

        float input = Strings.parseFloat(text.trim().replace(',', '.'), Float.NaN);
        if(Float.isNaN(input) || Float.isInfinite(input)) return Float.NaN;

        float val = Math.max(floor, input * scale);
        return Float.isInfinite(val) ? Float.NaN : val;
    }

    /** Диапазон слайдера всегда строится от базового и расширяется лишь настолько, чтобы вместить значение. */
    public static float rangeMin(float baseMin, float value){
        return Math.min(baseMin, value);
    }

    public static float rangeMax(float baseMax, float value){
        return Math.max(baseMax, value);
    }

    /** Текст значения слайдера в отображаемых единицах. */
    public static String format(float value, float scale){
        float v = value / scale;
        //целые - без дроби: autoFixed обрезает большие float (1000001 -> "1000000.9")
        if(Math.abs(v - Math.round(v)) <= Math.ulp(v) * 4f) return Long.toString(Math.round((double)v));
        return Strings.autoFixed(v, 2);
    }

    /**
     * @param baseMin диапазон слайдера из его конструктора
     * @return выставленное значение в единицах слайдера или NaN, если текст отвергнут и ничего не изменилось
     */
    public static float apply(Slider slider, float baseMin, float baseMax, float scale, float floor, String text){
        float val = parse(text, scale, floor);
        if(Float.isNaN(val)) return val;

        slider.setRange(rangeMin(baseMin, val), rangeMax(baseMax, val));

        //ProgressBar.setValue округляет до шага; шаг, равный |val|, сохраняет val точным
        float step = slider.getStepSize();
        if(val != 0f) slider.setStepSize(Math.abs(val));
        slider.setValue(val);
        slider.setStepSize(step);

        slider.change();
        return val;
    }
}
