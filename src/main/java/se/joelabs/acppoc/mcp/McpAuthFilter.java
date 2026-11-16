package se.joelabs.acppoc.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Kräver {@code Authorization: Bearer <token>} och lägger chatt-id som request-attribut (NFR-4).
 *
 * <p>Filtret avgör inte själv vilka sökvägar det gäller. Det registreras av {@link McpSecurityConfig} mot
 * {@code /mcp} och {@code /mcp/*}, som servletcontainern matchar mot den normaliserade sökvägen. Att jämföra den
 * råa request-URI:n här skulle kunna kringgås med t.ex. {@code //mcp} eller {@code /mcp;x}.
 */
public class McpAuthFilter extends OncePerRequestFilter {

    public static final String CHAT_ID_ATTRIBUTE = McpAuthFilter.class.getName() + ".chatId";
    private static final String BEARER_PREFIX = "bearer ";

    private final McpTokenService tokens;

    public McpAuthFilter(McpTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var chatId = tokens.resolve(bearerToken(request));
        if (chatId.isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        request.setAttribute(CHAT_ID_ATTRIBUTE, chatId.get());
        chain.doFilter(request, response);
    }

    /** Schemat är skiftlägesokänsligt enligt RFC 7235. */
    private static String bearerToken(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        var hasBearerScheme = header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
        return hasBearerScheme ? header.substring(BEARER_PREFIX.length()) : null;
    }
}
