package sonkaextras.accounts;

import arc.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;

import java.security.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/**
 * Менеджер аккаунтов (UUID): набор независимых игровых личностей, между которыми можно переключаться.
 * <p>
 * <b>Что такое «аккаунт» для сервера.</b> При входе клиент шлёт {@code ConnectPacket} с двумя идентификаторами:
 * {@code uuid} (8 байт в base64 - по нему сервер ведёт бан/админку/статистику) и {@code usid} (секрет «на сервер»:
 * по паре uuid+usid сервер признаёт админа; у каждого сервера свой, ключ ванили - {@code usid-<адрес>}).
 * Аккаунт = uuid + его набор usid по серверам: если при смене uuid оставить прежние usid, админка на сервере
 * сломается при возврате, поэтому они переключаются ВМЕСТЕ.
 * <p>
 * <b>Системный аккаунт.</b> Первый запуск создаёт аккаунт «stock» - это штатная личность клиента: uuid берётся у
 * {@code platform.getUUID()} (на Steam он выводится из Steam ID, в настройках его вообще нет), usid - из ванильных
 * ключей {@code usid-*}. Мы их НЕ копируем и НЕ меняем: штатные ключи остаются нетронутыми, поэтому оригинальная
 * личность (в том числе админская где-нибудь) не может быть потеряна ни сбросом, ни удалением, ни багом менеджера -
 * достаточно переключиться обратно. Именно поэтому системный аккаунт нельзя сбросить/перезаписать/удалить:
 * новую личность создают отдельным аккаунтом.
 * <p>
 * <b>Хук.</b> Всё сводится к двум вызовам из {@link mindustry.core.NetClient}: {@link #uuid()} вместо
 * {@code platform.getUUID()} и {@link #usid} в начале {@code getUsid}. Любой сбой менеджера (битый документ, мусор в
 * uuid) = тихий откат на штатное поведение: подключение не должно ломаться из-за менеджера аккаунтов.
 * Смена аккаунта действует со СЛЕДУЮЩЕГО подключения (текущая сессия уже представилась серверу).
 * <p>
 * <b>Хранение.</b> Один ключ {@value #SETTINGS_KEY} (JSON). В имени есть «uuid» - ванильная «очистить все данные»
 * (SettingsMenuDialog) оставляет любые ключи с {@code uuid}/{@code usid}, так что аккаунты переживают её.
 * Из экспорта/импорта данных ключ исключён ({@link sonkaextras.dataio.DataCategory#isProtectedSettingKey}): это
 * учётные данные, архивы люди пересылают друг другу.
 * Схема: {@code {"v":1,"active":"<id>","accounts":[{"id","name","stock","uuid","usids":{"<адрес>":"<usid>"},
 * "created","used"}]}}; битый документ откладывается в {@value #SETTINGS_KEY}{@code -corrupt}, не затирается.
 */
public final class Accounts{
    public static final String SETTINGS_KEY = "sonka-uuid-accounts";
    private static final String CORRUPT_KEY = SETTINGS_KEY + "-corrupt";

    private static final SecureRandom random = new SecureRandom();

    /** Один аккаунт. Поля меняются только через {@link Accounts}, диалоги читают. */
    public static class Account{
        public String id;
        public String name;
        /** Штатная личность клиента: uuid/usid берутся у платформы и из ванильных ключей, не хранятся здесь. */
        public boolean stock;
        /** base64 от 8 байт; для системного аккаунта null. */
        public String uuid;
        /** Нормализованный адрес сервера -> usid; для системного аккаунта пусто (живёт в {@code usid-*}). */
        public final OrderedMap<String, String> usids = new OrderedMap<>();
        public long created, lastUsed;

        /** uuid, который реально уйдёт серверу при подключении этим аккаунтом. */
        public String effectiveUuid(){
            return stock || !validUuid(uuid) ? platform.getUUID() : uuid;
        }

        /** Сколько серверов запомнено (для системного - ванильные {@code usid-*}). */
        public int serverCount(){
            if(!stock) return usids.size;
            int n = 0;
            for(String key : Core.settings.keys()){
                if(key.startsWith("usid-")) n++;
            }
            return n;
        }
    }

    private static final Seq<Account> accounts = new Seq<>();
    private static String activeId;
    private static boolean loaded;
    /** Если при загрузке документ оказался битым - показываем в диалоге, чтобы это не прошло молча. */
    public static boolean recoveredFromCorrupt;

    private Accounts(){}

    // ───────────────────────── проверки и вычисления ─────────────────────────

    /** uuid годится для пакета: ровно 8 байт в каноничном base64 (ConnectPacket.write декодирует и считает CRC по ним). */
    public static boolean validUuid(String s){
        if(s == null || s.length() != 12) return false;
        try{
            byte[] b = Base64Coder.decode(s);
            return b.length == 8 && new String(Base64Coder.encode(b)).equals(s);
        }catch(Throwable t){
            return false;
        }
    }

    /**
     * ID, под которым клиент виден СЕРВЕРУ: ConnectPacket.write кладёт 8 байт uuid и следом long с CRC32 этих байт,
     * а читает сервер 16 байт целиком и кодирует в base64 (см. {@code ConnectPacket.read}). Это то, что админ видит в
     * {@code /trace}, банах и списке админов, - в диалоге показываем его, чтобы можно было сверить.
     */
    public static String serverId(String uuid){
        try{
            byte[] b = Base64Coder.decode(uuid);
            CRC32 crc = new CRC32();
            crc.update(b, 0, b.length);
            long v = crc.getValue();
            byte[] out = new byte[b.length + 8];
            System.arraycopy(b, 0, out, 0, b.length);
            for(int i = 0; i < 8; i++){
                out[b.length + i] = (byte)(v >>> (56 - 8 * i));
            }
            return new String(Base64Coder.encode(out));
        }catch(Throwable t){
            return "?";
        }
    }

    /** Новый случайный uuid (SecureRandom: usid/uuid - идентификационные секреты, а не просто счётчик). */
    public static String generateUuid(){
        return randomB64(8);
    }

    private static String randomB64(int bytes){
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return new String(Base64Coder.encode(b));
    }

    /** Та же нормализация адреса, что в {@code NetClient.getUsid}: часть после «/» («домен/1.2.3.4:6567»). */
    static String normalizeAddress(String ip){
        if(ip != null && ip.contains("/")) ip = ip.substring(ip.indexOf("/") + 1);
        return ip;
    }

    // ───────────────────────── хук для NetClient ─────────────────────────

    /** uuid для ConnectPacket: активного аккаунта или штатный. Никогда не бросает. */
    public static String uuid(){
        try{
            ensureLoaded();
            Account a = active();
            if(a != null && !a.stock && validUuid(a.uuid)) return a.uuid;
        }catch(Throwable t){
            Log.err("[sonka-accounts] uuid() failed, using the stock one", t);
        }
        return platform.getUUID();
    }

    /**
     * usid для сервера {@code address} активного аккаунта; null = «используй ванильную логику»
     * (системный аккаунт или сбой). Для чужого аккаунта usid при первом входе на сервер создаётся и сразу сохраняется.
     */
    public static String usid(String address){
        try{
            ensureLoaded();
            Account a = active();
            if(a == null || a.stock || !validUuid(a.uuid)) return null;
            String key = normalizeAddress(address);
            String have = a.usids.get(key);
            if(have != null) return have;
            String made = randomB64(8);
            a.usids.put(key, made);
            save();
            Log.info("[sonka-accounts] new usid for '@' on @", a.name, key);
            return made;
        }catch(Throwable t){
            Log.err("[sonka-accounts] usid() failed, using the stock one", t);
            return null;
        }
    }

    // ───────────────────────── доступ ─────────────────────────

    public static Seq<Account> list(){
        ensureLoaded();
        return accounts;
    }

    public static Account active(){
        ensureLoaded();
        for(Account a : accounts){
            if(a.id.equals(activeId)) return a;
        }
        return accounts.isEmpty() ? null : accounts.first();
    }

    public static boolean isActive(Account a){
        Account act = active();
        return act != null && act == a;
    }

    /** Аккаунт, которому принадлежит этот uuid (кроме {@code except}), или null - для защиты от дубликатов. */
    public static Account byUuid(String uuid, Account except){
        for(Account a : list()){
            if(a != except && uuid.equals(a.effectiveUuid())) return a;
        }
        return null;
    }

    // ───────────────────────── операции ─────────────────────────

    /** Новый аккаунт со случайным uuid. Не активирует. */
    public static Account create(String name){
        ensureLoaded();
        Account a = new Account();
        a.id = Long.toHexString(random.nextLong());
        a.name = cleanName(name);
        a.uuid = freshUuid(null);
        a.created = Time.millis();
        accounts.add(a);
        save();
        return a;
    }

    public static void rename(Account a, String name){
        a.name = cleanName(name);
        save();
    }

    /**
     * Меняет uuid на заданный (ручной ввод/перенос с другого устройства). usid прежнего uuid бессмысленны для нового
     * и стираются. Системному менять нельзя.
     * @return null при успехе, иначе ключ бандла с причиной отказа
     */
    public static String setUuid(Account a, String uuid){
        if(a.stock) return "client.sonka.accounts.err.stock";
        if(!validUuid(uuid)) return "client.sonka.accounts.err.invalid";
        if(uuid.equals(a.uuid)) return null;
        if(byUuid(uuid, a) != null) return "client.sonka.accounts.err.duplicate";
        a.uuid = uuid;
        a.usids.clear();
        save();
        return null;
    }

    /** Сброс: новый случайный uuid + забыть usid. Системный не сбрасывается (см. javadoc класса). */
    public static void reset(Account a){
        if(a.stock) return;
        a.uuid = freshUuid(a);
        a.usids.clear();
        save();
    }

    /** Забыть usid всех серверов аккаунта (uuid остаётся). Для системного это ванильные {@code usid-*}: его не трогаем. */
    public static void forgetUsids(Account a){
        if(a.stock) return;
        a.usids.clear();
        save();
    }

    /** Делает аккаунт активным: со следующего подключения клиент представляется им. */
    public static void activate(Account a){
        ensureLoaded();
        activeId = a.id;
        a.lastUsed = Time.millis();
        save();
        Log.info("[sonka-accounts] active account: '@' (server id @)", a.name, serverId(a.effectiveUuid()));
    }

    /** Удаляет аккаунт. Активный и системный удалять нельзя - false. */
    public static boolean delete(Account a){
        if(a.stock || isActive(a)) return false;
        accounts.remove(a);
        save();
        return true;
    }

    private static String freshUuid(Account except){
        String u;
        do{
            u = generateUuid();
        }while(byUuid(u, except) != null);
        return u;
    }

    /** Строка из бандла; без бандла (headless-тесты) - запасной английский текст. */
    private static String tr(String key, String fallback){
        return Core.bundle != null ? Core.bundle.get(key) : fallback;
    }

    private static String cleanName(String name){
        name = name == null ? "" : name.trim();
        if(name.isEmpty()) name = tr("client.sonka.accounts.defaultname", "Account");
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    // ───────────────────────── хранение ─────────────────────────

    private static void ensureLoaded(){
        if(loaded) return;
        loaded = true;
        String raw = qol.core.SafeSettings.getString(SETTINGS_KEY, "");
        if(!raw.isEmpty()){
            try{
                read(Jval.read(raw));
            }catch(Throwable t){
                //документ битый: не затираем, а откладываем рядом и стартуем с чистого листа (системный аккаунт цел всегда)
                Log.err("[sonka-accounts] corrupt " + SETTINGS_KEY + ", starting from the stock account (backup in " + CORRUPT_KEY + ")", t);
                Core.settings.put(CORRUPT_KEY, raw);
                accounts.clear();
                activeId = null;
                recoveredFromCorrupt = true;
            }
        }
        if(!hasStock()){
            Account s = new Account();
            s.id = "stock";
            s.name = tr("client.sonka.accounts.stockname", "Original");
            s.stock = true;
            s.created = Time.millis();
            accounts.insert(0, s);
            if(activeId == null) activeId = s.id;
            save();
        }
        if(accounts.find(a -> a.id.equals(activeId)) == null) activeId = accounts.first().id;
    }

    private static boolean hasStock(){
        return accounts.contains(a -> a.stock);
    }

    private static void read(Jval root){
        if(!root.isObject()) throw new IllegalStateException("not an object");
        accounts.clear();
        Jval arr = root.get("accounts");
        if(arr != null && arr.isArray()){
            for(Jval j : arr.asArray()){
                if(!j.isObject()) continue;
                Account a = new Account();
                a.id = j.getString("id", Long.toHexString(random.nextLong()));
                a.name = j.getString("name", "?");
                a.stock = j.getBool("stock", false);
                a.uuid = a.stock ? null : j.getString("uuid", null);
                a.created = (long)j.getDouble("created", 0);
                a.lastUsed = (long)j.getDouble("used", 0);
                Jval us = j.get("usids");
                if(!a.stock && us != null && us.isObject()){
                    for(var e : us.asObject()){
                        if(e.value.isString()) a.usids.put(e.key, e.value.asString());
                    }
                }
                //аккаунт с негодным uuid не пропускаем молча: оставляем в списке, чтобы пользователь увидел и исправил/удалил;
                //при подключении uuid() всё равно откатится на штатный
                if(accounts.contains(x -> x.id.equals(a.id))) continue;
                accounts.add(a);
            }
        }
        activeId = root.getString("active", null);
    }

    private static void save(){
        try{
            Jval root = Jval.newObject();
            root.put("v", Jval.valueOf(1));
            root.put("active", Jval.valueOf(activeId == null ? "" : activeId));
            Jval arr = Jval.newArray();
            for(Account a : accounts){
                Jval j = Jval.newObject();
                j.put("id", Jval.valueOf(a.id));
                j.put("name", Jval.valueOf(a.name));
                if(a.stock){
                    j.put("stock", Jval.valueOf(true));
                }else{
                    j.put("uuid", Jval.valueOf(a.uuid == null ? "" : a.uuid));
                    Jval us = Jval.newObject();
                    for(var e : a.usids){
                        us.put(e.key, Jval.valueOf(e.value));
                    }
                    j.put("usids", us);
                }
                j.put("created", Jval.valueOf((double)a.created));
                j.put("used", Jval.valueOf((double)a.lastUsed));
                arr.asArray().add(j);
            }
            root.put("accounts", arr);
            if(qol.core.SafeSettings.putString(SETTINGS_KEY, root.toString(Jval.Jformat.plain))){
                //сразу на диск: потерянный usid = потерянная админка на сервере, потерянный аккаунт = потерянная личность
                Core.settings.forceSave();
            }
        }catch(Throwable t){
            Log.err("[sonka-accounts] save failed", t);
        }
    }

    /** Только для тестов: забыть кэш и перечитать из настроек. */
    public static void resetForTest(){
        accounts.clear();
        activeId = null;
        loaded = false;
        recoveredFromCorrupt = false;
    }
}
