package sonkaextras;

import arc.*;
import arc.struct.*;
import arc.util.io.*;
import mindustry.core.*;
import mindustry.net.Packets.*;
import org.junit.jupiter.api.*;
import sonkaextras.accounts.*;
import sonkaextras.accounts.Accounts.*;
import test.ApplicationTests;

import java.io.*;
import java.lang.reflect.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Менеджер аккаунтов (UUID) на настоящем движке: формат uuid/«ID на сервере» сверяется с реальным
 * {@code ConnectPacket.write -> read}, жизненный цикл аккаунтов, хуки {@code NetClient.getUsid} и сохранность
 * системной (штатной) личности при любых операциях.
 */
public class AccountsTest{
    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication(true);
    }

    @BeforeEach
    void setup(){
        Core.settings.remove(Accounts.SETTINGS_KEY);
        Core.settings.remove(Accounts.SETTINGS_KEY + "-corrupt");
        Accounts.resetForTest();
    }

    static String callGetUsid(String ip) throws Exception{
        //в headless-запуске netClient не создаётся; getUsid от состояния объекта не зависит, поэтому берём любой экземпляр
        if(netClient == null) netClient = new NetClient();
        Method m = NetClient.class.getDeclaredMethod("getUsid", String.class);
        m.setAccessible(true);
        return (String)m.invoke(netClient, ip);
    }

    @Test
    void validUuidAcceptsOnlyCanonicalEightBytes(){
        assertTrue(Accounts.validUuid(Accounts.generateUuid()));
        assertTrue(Accounts.validUuid("AAAAAAAAAAA="));
        assertFalse(Accounts.validUuid(null));
        assertFalse(Accounts.validUuid(""));
        assertFalse(Accounts.validUuid("AAAAAAAA"));            //6 байт
        assertFalse(Accounts.validUuid("AAAAAAAAAAAAAAAA"));    //12 байт, длина не 12 символов
        assertFalse(Accounts.validUuid("AAAAAAAAAAA!"));        //не base64
        assertFalse(Accounts.validUuid("AAAAAAAAAAB="));        //неканоничный хвост: перекодируется иначе
    }

    /** «ID на сервере» должен совпасть с тем, что сервер реально получит из пакета (read кодирует 16 байт). */
    @Test
    void serverIdMatchesWhatServerReadsFromRealPacket() throws Exception{
        for(int i = 0; i < 20; i++){
            String uuid = Accounts.generateUuid();
            ConnectPacket out = new ConnectPacket();
            out.versionType = "x"; out.name = "n"; out.locale = "en"; out.usid = "u";
            out.uuid = uuid; out.mods = new Seq<>(); out.mobile = false; out.color = 0;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.write(new Writes(new DataOutputStream(bytes)));

            ConnectPacket in = new ConnectPacket();
            in.read(new Reads(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
            assertEquals(in.uuid, Accounts.serverId(uuid), "uuid " + uuid);
        }
    }

    @Test
    void firstRunHasOnlyActiveStockAccountAndUsesStockIdentity() throws Exception{
        Seq<Account> list = Accounts.list();
        assertEquals(1, list.size);
        assertTrue(list.first().stock);
        assertTrue(Accounts.isActive(list.first()));

        assertEquals(platform.getUUID(), Accounts.uuid());
        assertNull(Accounts.usid("1.2.3.4:6567"), "системный аккаунт отдаёт usid ванильной логике");

        //getUsid у системного - ванильный путь: ключ usid-* создаётся в настройках
        Core.settings.remove("usid-1.2.3.4:6567");
        String vanilla = callGetUsid("host.example/1.2.3.4:6567");
        assertEquals(vanilla, Core.settings.getString("usid-1.2.3.4:6567", null));
    }

    @Test
    void switchingAccountChangesUuidAndKeepsPerServerUsidsSeparate() throws Exception{
        String stockUuid = platform.getUUID();
        Core.settings.remove("usid-9.9.9.9:6567");
        String stockUsid = callGetUsid("9.9.9.9:6567");

        Account alt = Accounts.create("Запасной");
        assertTrue(Accounts.validUuid(alt.uuid));
        assertNotEquals(stockUuid, alt.uuid);
        assertTrue(Accounts.isActive(Accounts.list().first()), "создание не должно активировать");

        Accounts.activate(alt);
        assertEquals(alt.uuid, Accounts.uuid());

        String u1 = callGetUsid("dom.example/9.9.9.9:6567");
        String u2 = callGetUsid("9.9.9.9:6567");
        assertEquals(u1, u2, "адрес нормализуется как в ванили");
        assertNotEquals(stockUsid, u1);
        assertEquals(stockUsid, Core.settings.getString("usid-9.9.9.9:6567", null), "ванильный ключ не тронут");

        //переключились обратно: прежняя личность целиком на месте
        Accounts.activate(Accounts.list().first());
        assertEquals(stockUuid, Accounts.uuid());
        assertEquals(stockUsid, callGetUsid("9.9.9.9:6567"));
    }

    @Test
    void resetAndSetUuidClearUsidsAndRejectBadInput() throws Exception{
        Account a = Accounts.create("A");
        Accounts.activate(a);
        callGetUsid("5.5.5.5:6567");
        assertEquals(1, a.usids.size);

        String before = a.uuid;
        Accounts.reset(a);
        assertNotEquals(before, a.uuid);
        assertEquals(0, a.usids.size);

        Account b = Accounts.create("B");
        assertEquals("client.sonka.accounts.err.invalid", Accounts.setUuid(b, "nope"));
        assertEquals("client.sonka.accounts.err.duplicate", Accounts.setUuid(b, a.uuid));
        String manual = "AQIDBAUGBwg=";
        assertNull(Accounts.setUuid(b, manual));
        assertEquals(manual, b.uuid);
    }

    @Test
    void stockAccountIsProtected(){
        Account stock = Accounts.list().first();
        String before = stock.effectiveUuid();
        Accounts.reset(stock);
        assertEquals(before, stock.effectiveUuid());
        assertEquals("client.sonka.accounts.err.stock", Accounts.setUuid(stock, Accounts.generateUuid()));
        assertFalse(Accounts.delete(stock));

        Account a = Accounts.create("A");
        Accounts.activate(a);
        assertFalse(Accounts.delete(a), "активный не удаляется");
        Accounts.activate(stock);
        assertTrue(Accounts.delete(a));
        assertEquals(1, Accounts.list().size);
    }

    @Test
    void accountsSurviveReloadFromSettings() throws Exception{
        Account a = Accounts.create("Persist");
        Accounts.activate(a);
        callGetUsid("7.7.7.7:6567");
        String uuid = a.uuid, usid = a.usids.get("7.7.7.7:6567");

        Accounts.resetForTest();
        assertEquals(2, Accounts.list().size);
        Account back = Accounts.active();
        assertEquals("Persist", back.name);
        assertEquals(uuid, back.uuid);
        assertEquals(usid, back.usids.get("7.7.7.7:6567"));
        assertEquals(usid, callGetUsid("7.7.7.7:6567"));
    }

    @Test
    void corruptDocumentIsBackedUpAndFallsBackToStock(){
        Core.settings.put(Accounts.SETTINGS_KEY, "{ this is not json");
        Accounts.resetForTest();

        assertEquals(1, Accounts.list().size);
        assertTrue(Accounts.active().stock);
        assertTrue(Accounts.recoveredFromCorrupt);
        assertEquals("{ this is not json", Core.settings.getString(Accounts.SETTINGS_KEY + "-corrupt", null));
        assertEquals(platform.getUUID(), Accounts.uuid());
    }

    @Test
    void garbageUuidOfActiveAccountFallsBackToStockInsteadOfBreakingConnect(){
        Account a = Accounts.create("Broken");
        Accounts.activate(a);
        a.uuid = "garbage";
        assertEquals(platform.getUUID(), Accounts.uuid());
        assertNull(Accounts.usid("1.1.1.1:6567"));
    }

    @Test
    void accountsKeyIsNeverExportedOrImported(){
        assertTrue(sonkaextras.dataio.DataCategory.isProtectedSettingKey(Accounts.SETTINGS_KEY));
        assertTrue(sonkaextras.dataio.DataCategory.isProtectedSettingKey(Accounts.SETTINGS_KEY + "-corrupt"));
        assertFalse(sonkaextras.dataio.DataCategory.isPlainSettingKey(Accounts.SETTINGS_KEY));
    }
}
