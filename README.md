<p align="center"><img src="monolith.png" width="160" alt="Monolith"></p>
<h1 align="center">Monolith</h1>
<p align="center">Самодостаточный клиент Mindustry со вшитыми QoL-модами. Форк <a href="https://github.com/mindustry-antigrief/mindustry-client">Foo's Client</a> (v8).<br>
<i>A self-contained Mindustry client with QoL mods baked in. Fork of Foo's Client (v8).</i></p>

## Что это
Один jar вместо клиента + кучи модов. Из Foo's Client убраны анти-гриф/модерационные инструменты, оставлены QoL и серверные интеграции, и поверх этого нативным кодом вшиты:

Extended UI++, MI2-Utilities, Agzam's Mod, Testing Utilities, Mapping Utilities, Too Many Items, Scheme Size, Mindustry Tool, Helium (менеджер модов), New Console Hardline, PatchEditor, Extra Editor, QoL Suite, QoL Control, Campaign Utils, Bridge To Core - плюс собственные фичи (теги карт, таблица и теги схем, профили кампании, экспорт/импорт данных, пер-панельный масштаб HUD, редактор курсоров и т.д.).

Все вшитые моды живут на общей вкладке **«Моды»** в настройках; обзор - кнопка **Features** в главном меню.

## Установка
1. В обычном Mindustry положите в папку модов [`sonka-client-installer.jar`](https://github.com/ARCYDE0078/mindustry-client-installer/releases/latest) и запустите игру.
2. Нажмите «Установить» - клиент скачается из [последнего релиза](../../releases/latest) и встанет на место jar-файла игры.
3. Дальше клиент обновляется сам по каналу релизов `custom-b*` (настройки «Репозиторий обновлений» / «Автоматически проверять обновления клиента» на вкладке Client).

Вручную: скачайте `Mindustry-custom-desktop.jar` из [релиза](../../releases/latest) и запустите `java -jar` (нужна Java 17+).

## Сборка
```
./gradlew desktop:dist        # jar в desktop/build/libs/Mindustry.jar
```
Нужен JDK 17. Релиз: тег `custom-bN` → GitHub Actions соберёт и опубликует сборку.

## Ссылки
- [Changelog](./core/assets/changelog)
- [Установщик](https://github.com/ARCYDE0078/mindustry-client-installer)
- [Foo's Client](https://github.com/mindustry-antigrief/mindustry-client) - апстрим; лицензия GPL-3.0 сохранена.
