package se.joelabs.acppoc.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param publicUrl adressen agenten använder mot MCP-servern
 * @param devToken utvecklartoken för t.ex. MCP Inspector; tom = avstängd (NFR-4)
 */
@ConfigurationProperties("poc.mcp")
public record McpProperties(String publicUrl, String devToken) {}
