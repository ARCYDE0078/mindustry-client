package mindustrytool.features.chat.translation;

import java.util.regex.Pattern;

import arc.util.Nullable;
import arc.util.Strings;

/**
 * Решает, стоит ли отправлять входящее сообщение чата на перевод. Без фильтра в провайдера (а это платные/лимитные
 * API) уходили координаты, ссылки, base64-схемы, "gg" и серверные уведомления - лишние запросы и мусор в чате вида
 * "x: 12 y: 40 (x: 12 y: 40)". Порт ChatFilter апстрима v5.5.27; правила для ИСХОДЯЩИХ сообщений не берём - у нас
 * исходящий перевод не подключён.
 */
public final class ChatFilter {

    private static final Pattern SYSTEM_OR_BOT_PREFIX = Pattern.compile(
            "^(?:\\[|<|\\()\\s*(?:server|system|host|admin|discord|irc|bridge|bot|telegram|tg|console)\\s*(?:\\]|>|\\))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COORDINATES_PATTERN = Pattern.compile(
            "^(?:x\\s*[:=]\\s*-?\\d+\\s*,?\\s*y\\s*[:=]\\s*-?\\d+|\\(\\s*-?\\d+\\s*[, ]\\s*-?\\d+\\s*\\)|-?\\d+\\s*[, ]\\s*-?\\d+)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(?:https?://|www\\.)\\S+$",
            Pattern.CASE_INSENSITIVE);

    /* схема Mindustry в base64 всегда начинается с "msch" -> "bXNj"; префикс "bXN" берём с запасом на разную упаковку */
    private static final Pattern SCHEMATIC_BASE64 = Pattern.compile(
            "^bXN[a-zA-Z0-9+/=_-]{20,}$");

    private static final Pattern NUMBERS_ONLY = Pattern.compile(
            "^-?\\d+(?:[.,]\\d+)?$");

    private static final Pattern SYSTEM_EVENT_PHRASES = Pattern.compile(
            "(?:has connected|has disconnected|đã kết nối|đã ngắt kết nối|joined the game|left the game|server restarting|game over|next wave in)",
            Pattern.CASE_INSENSITIVE);

    private static final String[] UNIVERSAL_ACRONYMS = {
            "gg", "ggwp", "glhf", "gl", "hf", "ty", "np", "thx", "pls", "plz", "wp", "afk", "brb", "omg", "wtf", "xd",
            "lol"
    };

    private ChatFilter() {
    }

    public static boolean shouldTranslateIncoming(@Nullable String raw) {
        if (raw == null) {
            return false;
        }
        String clean = Strings.stripColors(raw).trim();
        if (clean.isEmpty()) {
            return false;
        }

        if (isSystemOrBotMessage(clean) || SYSTEM_EVENT_PHRASES.matcher(clean).find()) {
            return false;
        }

        String content = extractMessageContent(clean);
        if (content.isEmpty()) {
            return false;
        }

        return !isNoise(content);
    }

    public static boolean isSystemOrBotMessage(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        return SYSTEM_OR_BOT_PREFIX.matcher(text.trim()).find();
    }

    public static boolean isNoise(@Nullable String text) {
        if (text == null) {
            return true;
        }
        String clean = Strings.stripColors(text).trim();
        if (clean.isEmpty()) {
            return true;
        }

        if (NUMBERS_ONLY.matcher(clean).matches()
                || COORDINATES_PATTERN.matcher(clean).matches()
                || URL_PATTERN.matcher(clean).matches()
                || isSchematic(clean)
                || isPunctuationOrEmoticonOnly(clean)) {
            return true;
        }

        String lower = clean.toLowerCase();
        for (String acronym : UNIVERSAL_ACRONYMS) {
            if (lower.equals(acronym)) {
                return true;
            }
        }

        return false;
    }

    public static boolean isSchematic(@Nullable String text) {
        if (text == null || text.length() < 20) {
            return false;
        }
        return SCHEMATIC_BASE64.matcher(text.trim()).matches();
    }

    public static boolean isPunctuationOrEmoticonOnly(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }

        boolean hasLetter = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                hasLetter = true;
                break;
            }
        }

        if (!hasLetter) {
            return true;
        }

        String trimmed = text.trim();
        if (trimmed.length() <= 4) {
            String lower = trimmed.toLowerCase();
            return lower.equals(":d") || lower.equals("xd") || lower.equals(":p") || lower.equals("xp")
                    || lower.equals("d:") || lower.equals("q_q") || lower.equals("t_t") || lower.equals("o_o")
                    || lower.equals("u_u") || lower.equals("o/");
        }

        return false;
    }

    /** Текст после "Ник:" - сам ник и префикс чата переводить не нужно, а фильтровать надо по тому, что написал игрок. */
    public static String extractMessageContent(@Nullable String cleanText) {
        if (cleanText == null) {
            return "";
        }
        int colonIdx = cleanText.indexOf(':');
        if (colonIdx >= 0 && colonIdx < cleanText.length() - 1) {
            return cleanText.substring(colonIdx + 1).trim();
        }
        return cleanText.trim();
    }
}
