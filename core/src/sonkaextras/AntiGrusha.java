package sonkaextras;

import arc.Core;
import arc.Events;
import arc.struct.ObjectMap;
import arc.util.Log;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.util.regex.Pattern;

import static mindustry.Vars.*;

/**
 * «Анти-груша»: делает почти прозрачные ники игроков яркими и видимыми - в мире, на миникарте и в списке
 * игроков (идея Amie, Anti-Grusha-mod v1.0; у оригинала был только скомпилированный класс без исходников,
 * поэтому здесь переписано заново по его байткоду - логика и ключ настройки те же).
 * <p>
 * Приём: кто-то называется, например, {@code [#ff000001]Груша} - альфа 01 в цветовом теге, и ник на экране
 * невидим (на списке игроков и над юнитом - пустое место). Мы раз в кадр переписываем ЛОКАЛЬНОЕ поле
 * {@code Player.name}: {@code [#RRGGBBAA]} -> {@code [#RRGGBBff]}, {@code [#RGBA]} -> {@code [#RGBf]},
 * {@code [clear]} -> {@code [white]}, а {@code Player.color.a} принудительно 1. Сервер ничего не узнаёт -
 * его копия ника не меняется, а снапшоты перезаписывают поле, поэтому правка повторяется каждый кадр (до
 * отрисовки - {@link Trigger#preDraw} - и в {@link Trigger#update}).
 * <p>
 * Дёшево: к regexp идут только ники с {@code '['} (иначе тегов нет), результат кэшируется по исходной
 * строке (кэш сбрасывается на 2000 записей), а в самом кэше фиксированная строка маппится на себя, чтобы
 * уже исправленный ник (поле остаётся исправленным до следующего снапшота) не гонять через regexp заново.
 * <p>
 * Настройка {@link #key} - тот же ключ, что у исходного мода (по умолчанию ВКЛ), живёт в секции Sonka Extras.
 */
public final class AntiGrusha{
    public static final String key = "anti-grusha-enabled";

    private static final Pattern HEX8 = Pattern.compile("\\[#([0-9a-fA-F]{6})[0-9a-fA-F]{2}\\]");
    private static final Pattern HEX4 = Pattern.compile("\\[#([0-9a-fA-F]{3})[0-9a-fA-F]\\]");
    private static final Pattern CLEAR = Pattern.compile("(?i)\\[clear\\]");

    private static final ObjectMap<String, String> cache = new ObjectMap<>();
    private static boolean errorLogged;

    private AntiGrusha(){
    }

    /** Вызывается из Main.kt до ClientLoadEvent - только вешает слушатели. */
    public static void init(){
        if(headless) return;

        Events.run(Trigger.update, AntiGrusha::safeUpdate);
        Events.run(Trigger.preDraw, AntiGrusha::safeUpdate);
    }

    private static void safeUpdate(){
        try{
            update();
        }catch(Throwable t){
            //один раз в лог, дальше молча - иначе ошибка спамила бы каждый кадр
            if(!errorLogged){
                errorLogged = true;
                Log.err("[anti-grusha]", t);
            }
        }
    }

    private static void update(){
        if(!state.isGame() || !Core.settings.getBool(key, true)) return;

        for(Player p : Groups.player){
            String name = p.name;
            if(name != null && name.indexOf('[') >= 0){
                String fixed = cache.get(name);
                if(fixed == null){
                    if(cache.size > 2000) cache.clear();
                    fixed = fix(name);
                    cache.put(name, fixed);
                    cache.put(fixed, fixed); //уже исправленный ник тоже кэшируем - не гонять его через regexp снова
                }
                if(!fixed.equals(name)) p.name = fixed;
            }

            if(p.color != null && p.color.a < 1f) p.color.a = 1f;
        }
    }

    /** Чистая функция замены тегов (вынесена для тестов): прозрачность -> непрозрачность. */
    static String fix(String name){
        String out = HEX8.matcher(name).replaceAll("[#$1ff]");
        out = HEX4.matcher(out).replaceAll("[#$1f]");
        return CLEAR.matcher(out).replaceAll("[white]");
    }
}
