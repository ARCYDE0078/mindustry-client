package sectorstats;

import arc.Core;
import arc.Events;
import mindustry.game.EventType.SectorLaunchEvent;
import mindustry.type.Sector;

import static mindustry.Vars.net;

/**
 * "Перенаправление пусковых площадок при высадке": когда игрок высаживается на сектор X с сектора-
 * источника O, площадки в O автоматически начинают слать ресурсы в X - логичное продолжение того, что
 * движок и так делает сам ({@code Control.playNewSector}: {@code sector.info.destination = origin}, т.е.
 * площадки СВЕЖЕГО сектора сами уже смотрят домой, в O). Без этого шага площадки в O молча продолжают
 * слать во что бы они ни были нацелены раньше - обычно в сектор, с которого шла высадка НА O, то есть
 * "на один сектор назад" от того, где игрок реально сейчас строится.
 * <p>
 * Код - зеркало ванильного паттерна смены {@code SectorInfo.destination} из
 * {@code LaunchPad.buildConfiguration()} (тот же {@code prev} + {@code refreshImportRates}), но O здесь
 * НЕ активно играемый сектор (в отличие от {@code state.rules.sector} у ванильной кнопки), поэтому
 * изменение {@code info} нужно сохранить явно через {@link Sector#saveInfo()} - у активного сектора это
 * делает штатный автосейв, здесь его нет.
 * <p>
 * Слушает {@link SectorLaunchEvent}, который движок фаерит в {@code playNewSector} ПОСЛЕ того как уже
 * записал {@code sector.info.origin}, так что источник высадки просто читается оттуда - искать его
 * отдельно (как это делает {@link sonkaextras.CampaignRetry}, которому origin нужен ДО высадки) не нужно.
 */
final class LaunchPadAutoRedirect{
    static final String settingKey = "campaignutils-auto-redirect-pads";

    private LaunchPadAutoRedirect(){
    }

    /** Вызывается из {@link CampaignUtilsMod}'s ClientLoadEvent-блока - только вешает слушатель. */
    static void init(){
        Events.on(SectorLaunchEvent.class, e -> {
            if(net.client() || !Core.settings.getBool(settingKey, true)) return;

            Sector landed = e.sector;
            Sector origin = landed.info.origin;
            //без источника (стартовый/free-launch сектор) или высадка "на месте" - перенаправлять некуда
            if(origin == null || origin == landed) return;
            //как и ванильный UI-пикер (LaunchPad.buildConfiguration: other.planet == ...planet) -
            //eachImport() всё равно считает только площадки того же планетоида, кросс-планетный
            //redirect был бы просто мёртвой меткой без эффекта на статистику импорта
            if(origin.planet != landed.planet) return;
            if(origin.info.destination == landed) return;

            var prev = origin.info.destination;
            origin.info.destination = landed;
            origin.saveInfo();
            if(prev != null){
                prev.info.refreshImportRates(origin.planet);
            }
        });
    }
}
