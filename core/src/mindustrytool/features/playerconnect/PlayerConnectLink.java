package mindustrytool.features.playerconnect;

import java.net.URI;
import java.net.URISyntaxException;

public class PlayerConnectLink {
    public static final String UriScheme = "player-connect";

    public final URI uri;
    public final String host;
    public final int port;
    public final String roomId;

    public PlayerConnectLink(String host, int port, String roomId) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Missing host");
        }

        //порт > 65535 раньше проходил проверку и падал уже глубоко в сокете с невнятной ошибкой
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port number: " + port);
        }

        if (roomId != null && roomId.startsWith("/")) {
            roomId = roomId.substring(1);
        }

        if (roomId == null || roomId.isEmpty()) {
            throw new IllegalArgumentException("Missing room id");
        }

        this.host = host;
        this.port = port;
        this.roomId = roomId;

        try {
            uri = new URI(UriScheme, null, host, port, '/' + roomId, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid host", e);
        }
    }

    @Override
    public String toString() {
        return uri.toString();
    }

    public static PlayerConnectLink fromString(String link) {
        if (link == null) {
            throw new IllegalArgumentException("Link cannot be null");
        }

        if (link.startsWith(UriScheme) &&
                (!link.startsWith(UriScheme + "://") || link.length() == (UriScheme + "://").length()))
            throw new IllegalArgumentException("Missing host");

        URI uri;
        try {
            uri = URI.create(link);
        } catch (IllegalArgumentException e) {
            String cause = e.getLocalizedMessage();
            int semicolon = cause != null ? cause.indexOf(':') : -1;
            if (semicolon == -1)
                throw e;
            else
                throw new IllegalArgumentException(cause.substring(0, semicolon), e);
        }

        if (uri.isAbsolute() && !UriScheme.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Not a player-connect link: " + link);
        }

        return new PlayerConnectLink(uri.getHost(), uri.getPort(), uri.getPath());
    }

    public static boolean isValid(String link) {
        //пустая строка из буфера обмена - частый случай; null/пробелы не должны кидать, это просто "не ссылка"
        if (link == null || link.trim().isEmpty()) {
            return false;
        }

        try {
            fromString(link);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
