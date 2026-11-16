package se.joelabs.acppoc.mcp;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registrerar {@link McpAuthFilter} för /mcp med container-matchade URL-mönster (NFR-4). */
@Configuration
class McpSecurityConfig {

    @Bean
    FilterRegistrationBean<McpAuthFilter> mcpAuthFilter(McpTokenService tokens) {
        var registration = new FilterRegistrationBean<>(new McpAuthFilter(tokens));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(0);
        return registration;
    }
}
