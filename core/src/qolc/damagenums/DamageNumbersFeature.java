package qolc.damagenums;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Font;
import arc.scene.ui.layout.Scl;
import arc.struct.IntMap;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Time;
import mindustry.Vars;
import mindustry.client.CommandsKt;
import mindustry.entities.Damage;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType.BuildDamageEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnitDamageEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.ui.Fonts;

/**
 * Всплывающие числа урона над всем, что получает попадание ({@code !hp}). Порт features/hp.js.
 * <p>
 * Не путать с {@code eui} HP-барами юнитов (полоска здоровья над юнитом): здесь именно ЧИСЛО нанесённого
 * урона, которое всплывает и тает. Нативного аналога в клиенте нет.
 * <p>
 * Движок стреляет двумя событиями: {@link UnitDamageEvent} (юниты) и {@link BuildDamageEvent}
 * (здания), оба несут пулю. На клиенте они приходят тоже - пули симулируются локально, поэтому числа
 * видны по всей карте, а не только рядом с игроком. ВАЖНО: экземпляры обоих событий ПЕРЕИСПОЛЬЗУЮТСЯ
 * движком ({@code set(...)} на статическом поле), поэтому поля читаются синхронно в обработчике и
 * нигде не кешируются.
 * <p>
 * Урон по юнитам МЕРЯЕТСЯ разницей {@code health + shield} с прошлого попадания (взрывы и прочий
 * непрямой урон так видны честно); когда базы для сравнения нет (первое попадание или прошло больше
 * секунды) - считается по формуле движка из пули. У зданий на клиенте здоровье не меняется, поэтому
 * число всегда считается из пули по формуле {@code Building.collision}. Событие здания приходит и от
 * сплэш-урона - его формула из пули чуть завышает (сплэш не проходит через {@code buildingDamage}), но
 * разброс на глаз не заметен, а точнее клиенту узнать нечем.
 * <p>
 * Производительность: попадание только кладёт маленькую запись в список; рисуется всё за один проход
 * за кадр; число записей на экране ограничено (при переполнении вытесняется самая старая); попадания
 * по одной цели в коротком окне склеиваются в одно число - поток пуль не превращается в стену цифр.
 * <p>
 * Отличия от оригинала: по умолчанию ВЫКЛЮЧЕНО (у оригинала включено) - иначе в клиенте у sonka
 * внезапно появился бы постоянный визуал; прозрачность числа реально применяется (оригинал передавал
 * шрифту непрозрачный цвет, и затухание {@code Draw.color} на текст не влияло); размер шрифта делится
 * на масштаб интерфейса, как у прочих мировых подписей клиента, - число не раздувается на телефоне.
 * Диалог настроек оригинала заменён обычными пунктами секции "QoL Control" (флажок и четыре ползунка).
 */
public final class DamageNumbersFeature{
    private static final Color colorSmall = Color.valueOf("ffffff");
    private static final Color colorMedium = Color.valueOf("ffd37f");
    private static final Color colorBig = Color.valueOf("ff5c5c");
    private static final Color tmp = new Color();

    private static final Seq<Pop> numbers = new Seq<>();
    /** Последнее число цели - чтобы склеивать в него следующие попадания. Ключ: {@link #key}. */
    private static final IntMap<Pop> byTarget = new IntMap<>();
    /** Последнее известное здоровье юнитов - база для измерения урона. */
    private static final IntMap<Snapshot> hpCache = new IntMap<>();
    private static long lastCleanup = 0;

    private static class Pop{
        float x, y, amount;
        long time;
        int target;
    }

    private static class Snapshot{
        float hp;
        long time;
    }

    private DamageNumbersFeature(){
    }

    public static void init(){
        CommandsKt.register("hp [value]", Core.bundle.get("client.command.hp.description"), DamageNumbersFeature::command);

        Events.on(UnitDamageEvent.class, DamageNumbersFeature::onUnitDamage);
        Events.on(BuildDamageEvent.class, DamageNumbersFeature::onBuildDamage);
        Events.on(WorldLoadEvent.class, e -> clear());
        Events.run(Trigger.draw, DamageNumbersFeature::draw);
    }

    private static boolean enabled(){
        //по умолчанию выключено - см. javadoc класса
        return Core.settings.getBool("qol-hp-enabled", false);
    }

    private static void command(String[] args, Player player){
        boolean now = enabled();
        String arg = args.length == 0 ? "" : args[0].toLowerCase();
        switch(arg){
            case "1", "true", "on" -> now = true;
            case "0", "false", "off" -> now = false;
            case "" -> now = !now;
            default -> {
                player.sendMessage(Core.bundle.get("qolc.hp.usage"));
                return;
            }
        }
        Core.settings.put("qol-hp-enabled", now);
        if(!now) clear();
        player.sendMessage(Core.bundle.format("qolc.hp.toggled", Core.bundle.get(now ? "qolc.on" : "qolc.off")));
    }

    /** Ключ цели: у юнитов и зданий id приходят из разных счётчиков и могут совпасть - младший бит различает. */
    private static int key(int id, boolean building){
        return (id << 1) | (building ? 1 : 0);
    }

    private static void clear(){
        numbers.clear();
        byTarget.clear();
        hpCache.clear();
    }

    /**
     * Урон пули по юниту ровно как {@code ShieldComp.damage}: сперва броня (если пуля её не пробивает),
     * потом множители здоровья юнита и правил.
     */
    private static float unitBulletDamage(Unit unit, Bullet bullet){
        if(bullet == null) return 0f;
        BulletType type = bullet.type;
        float amount = bullet.damage;
        if(!type.pierceArmor){
            float armor = unit.armorOverride >= 0f ? unit.armorOverride : unit.armor;
            if(type.armorMultiplier != 1f) armor *= type.armorMultiplier;
            amount = Damage.applyArmor(amount, armor);
        }
        return amount / unit.healthMultiplier / Vars.state.rules.unitHealth(unit.team);
    }

    /** Урон пули по зданию ровно как {@code Building.collision}: урон по зданиям, броня блока, правило здоровья блоков команды. */
    private static float buildBulletDamage(Building build, Bullet bullet){
        if(bullet == null) return 0f;
        BulletType type = bullet.type;
        float amount = type.buildingDamage(bullet);
        if(!type.pierceArmor){
            amount = Damage.applyArmor(amount, build.block.armor * type.armorMultiplier * type.blockArmorMultiplier);
        }
        return amount / Vars.state.rules.blockHealth(build.team);
    }

    /** Реальный урон юниту: разница с его здоровьем на прошлом попадании; без базы - формула из пули. */
    private static float measureDamage(Unit unit, Bullet bullet){
        long now = Time.millis();
        float current = unit.health + unit.shield;
        Snapshot previous = hpCache.get(unit.id);
        if(previous == null){
            previous = new Snapshot();
            hpCache.put(unit.id, previous);
            //базы ещё не было - здоровье уже уменьшено, поэтому урон пересчитывается из пули так, как его применил бы движок
            previous.hp = current;
            previous.time = now;
            return unitBulletDamage(unit, bullet);
        }
        float diff = previous.hp - current;
        boolean fresh = now - previous.time < 1000;
        previous.hp = current;
        previous.time = now;
        if(fresh && diff > 0f) return diff;
        return unitBulletDamage(unit, bullet);
    }

    private static void addNumber(float x, float y, float amount, int target){
        long now = Time.millis();
        int mergeWindow = Core.settings.getInt("qol-hp-merge", 350);

        //склейка с уже висящим числом этой цели
        if(mergeWindow > 0){
            Pop other = byTarget.get(target);
            if(other != null && now - other.time < mergeWindow){
                other.amount += amount;
                other.time = now;
                other.x = x;
                other.y = y;
                return;
            }
        }

        if(numbers.size >= Math.max(1, Core.settings.getInt("qol-hp-max", 100))){
            Pop dropped = numbers.remove(0);
            if(byTarget.get(dropped.target) == dropped) byTarget.remove(dropped.target);
        }

        Pop pop = new Pop();
        pop.x = x;
        pop.y = y;
        pop.amount = amount;
        pop.time = now;
        pop.target = target;
        numbers.add(pop);
        byTarget.put(target, pop);
    }

    private static void onUnitDamage(UnitDamageEvent e){
        if(!enabled()) return;
        Unit unit = e.unit;
        if(unit == null || !unit.isValid()) return;
        float amount = measureDamage(unit, e.bullet);
        if(amount < Core.settings.getInt("qol-hp-min-damage", 1)) return;
        addNumber(unit.x, unit.y + unit.hitSize * 0.7f + 4f, amount, key(unit.id, false));
    }

    private static void onBuildDamage(BuildDamageEvent e){
        if(!enabled()) return;
        Building build = e.build;
        if(build == null || !build.isValid()) return;
        float amount = buildBulletDamage(build, e.source);
        if(amount < Core.settings.getInt("qol-hp-min-damage", 1)) return;
        addNumber(build.x, build.y + build.block.size * 4f, amount, key(build.id, true));
    }

    private static Color colorFor(float amount){
        if(amount >= 80f) return colorBig;
        if(amount >= 25f) return colorMedium;
        return colorSmall;
    }

    private static void draw(){
        if(!enabled()) return;

        long now = Time.millis();
        cleanupCache(now);
        if(numbers.isEmpty()) return;

        long lifetime = Math.max(200, Core.settings.getInt("qol-hp-lifetime", 1200));

        Font font = Fonts.outline;
        Draw.z(Layer.overlayUI);
        for(int i = numbers.size - 1; i >= 0; i--){
            Pop pop = numbers.get(i);
            long age = now - pop.time;
            if(age >= lifetime){
                numbers.remove(i);
                if(byTarget.get(pop.target) == pop) byTarget.remove(pop.target);
                continue;
            }

            float t = (float)age / lifetime;
            //быстро проявляется, медленно тает
            float alpha = t < 0.15f ? t / 0.15f : 1f - (t - 0.15f) / 0.85f;
            //число "выпрыгивает" и оседает до своего размера
            float popScale = age < 120 ? 1f + 0.45f * (1f - age / 120f) : 1f;

            tmp.set(colorFor(pop.amount)).a(alpha);
            //шрифт - в пикселях экрана, мир - в world-юнитах: делим на масштаб интерфейса, иначе на телефоне число втрое крупнее
            font.draw("-" + Math.round(pop.amount), pop.x, pop.y + t * 16f, tmp, 0.3f * popScale / Scl.scl(1f), true, Align.center);
        }
        Draw.reset();
    }

    /** Забываем здоровье юнитов, по которым давно не попадали (раз в 2 с - дешевле, чем каждый кадр). */
    private static void cleanupCache(long now){
        if(now - lastCleanup <= 2000) return;
        lastCleanup = now;
        var it = hpCache.entries().iterator();
        while(it.hasNext()){
            if(now - it.next().value.time > 5000) it.remove();
        }
    }
}
