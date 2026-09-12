package mindustrytool;

import java.util.Arrays;
import java.util.List;

import mindustrytool.dto.Sort;

/**
 * Порт Mindustry Tool (Sharlotte/MindustryVN, v4.59.1-v8) в клиент.
 * Конфиг обрезан: выкинуты DEV-режим, GitHub-репо мода и vercel-трекер задач —
 * это инфраструктура самого мода, встроенной версии они не нужны.
 */
public class Config {

    public static final String API_URL = "https://api.mindustry-tool.com/api/v4/";
    public static final String API_v4_URL = API_URL;
    public static final String IMAGE_URL = API_v4_URL;

    public static final String WEB_URL = "https://mindustry-tool.com";
    public static final String UPLOAD_SCHEMATIC_URL = WEB_URL + "/schematics?upload=true";
    public static final String UPLOAD_MAP_URL = WEB_URL + "/maps?upload=true";

    /** Версия мода, от которой сделан порт; уходит в статистику комнат Player Connect. */
    public static final String PORT_VERSION = "v4.59.1-v8";

    public static final List<Sort> sorts = Arrays.asList(//
            new Sort("newest", "time_desc"), //
            new Sort("oldest", "time_asc"), //
            new Sort("most-download", "download-count_desc"), //
            new Sort("most-like", "like_desc"));
}
