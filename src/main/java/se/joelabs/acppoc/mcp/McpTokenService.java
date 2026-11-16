package se.joelabs.acppoc.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Utfärdar en slumpad bearer-token per Chatt och slår upp chatt-id från token (ADR-0003, NFR-4). */
@Service
public class McpTokenService {

    static final String DEV_CHAT_ID = "dev";
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> chatIdByToken = new ConcurrentHashMap<>();
    private final String devToken;

    public McpTokenService(McpProperties properties) {
        this.devToken = properties.devToken() == null ? "" : properties.devToken();
    }

    /** Utfärdar en ny slumpad token (32 byte, Base64URL) för Chatten {@code chatId}. */
    public String issue(String chatId) {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        chatIdByToken.put(token, chatId);
        return token;
    }

    /** Ogiltigförklarar token, t.ex. när Chatten stängs. */
    public void revoke(String token) {
        chatIdByToken.remove(token);
    }

    /** @return chatt-id för giltig token, annars tomt */
    public Optional<String> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (isDevToken(token)) {
            return Optional.of(DEV_CHAT_ID);
        }
        return Optional.ofNullable(chatIdByToken.get(token));
    }

    private boolean isDevToken(String token) {
        return !devToken.isBlank()
                && MessageDigest.isEqual(
                        devToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
