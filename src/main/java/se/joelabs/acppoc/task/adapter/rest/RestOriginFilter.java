package se.joelabs.acppoc.task.adapter.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Skyddar ändrande REST-anrop mot CSRF (NFR-10). Appen saknar inloggning, så en annan webbplats i användarens
 * webbläsare annars kunde skicka anrop till localhost. Kräver {@code Origin} ur de tillåtna och JSON som innehållstyp
 * (ett formulär från en annan sida kan inte sätta den). Registreras av {@link TaskRestConfig} mot {@code /api/*}.
 */
class RestOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> allowedOrigins;

    RestOriginFilter(Set<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod()) || isTrusted(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    private boolean isTrusted(HttpServletRequest request) {
        var origin = request.getHeader("Origin");
        return origin != null && allowedOrigins.contains(origin) && isJson(request.getContentType());
    }

    private static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json");
    }
}
