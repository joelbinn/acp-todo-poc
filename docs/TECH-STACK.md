# Tech stack

Versionerna verifierades 2026-11-12 mot `repo1.maven.org` (`maven-metadata.xml`), npm-registret och respektive källrepo. Sök-API:t på `search.maven.org` visade gamla versioner vid kontrollen och ska inte användas. Versionerna låses i `pom.xml` och README.

## Runtime och ramverk

| Komponent | Version | Koordinat / paket | Kommentar |
| --- | --- | --- | --- |
| Java | 25 (LTS) | – | Se [Java 25](#java-25) |
| Spring Boot | 4.1.2 | `org.springframework.boot:spring-boot-starter-parent` | Stöder Java 17–27. Maven med wrapper. |
| Spring Web MVC | via Boot | `spring-boot-starter-web` | Serverar statiska filer och `/mcp` |
| Spring WebSocket | via Boot | `spring-boot-starter-websocket` | Rå `TextWebSocketHandler` |
| Jackson | 3 (via Boot 4) | – | `UiEvent` som sealed interface med `type`-fält |

## MCP-server

| Komponent | Version | Koordinat | Kommentar |
| --- | --- | --- | --- |
| Spring AI BOM | 2.0.2 | `org.springframework.ai:spring-ai-bom` | Används bara för MCP-servern |
| MCP-server-starter | via BOM | `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` | `protocol: STREAMABLE`, `type: SYNC`, `@McpTool` |
| MCP Java SDK | 2.1.0 (via BOM) | `io.modelcontextprotocol.sdk:mcp` | Klienten används i `McpEndpointIT` |

Att Spring AI 2.0.2 officiellt stöder Boot 4.1.2 var inte bekräftat vid kontrollen. **Bekräftat 2026-11-12 (ärende 01):** appen startar på Boot 4.1.2 med Spring AI 2.0.2, MCP-autokonfigurationen startar och `/mcp` fungerar mot MCP Java SDK 2.1.0-klient. Ingen sänkning av Boot behövdes.

## ACP-klient

| Komponent | Version | Koordinat | Kommentar |
| --- | --- | --- | --- |
| ACP Java SDK, kärna | 0.81.0 | `com.agentclientprotocol:acp-core` | `acp-java-sdk` är bara parent-POM |
| JSON-modul | 0.81.0 | `com.agentclientprotocol:acp-json-jackson3` | Krävs sedan 0.18.0. Jackson 3 matchar Boot 4. |
| Testtransport | 0.81.0 | `com.agentclientprotocol:acp-test` (test) | `InMemoryTransportPair` |
| Agent-stöd | 0.81.0 | `com.agentclientprotocol:acp-agent-support` (test) | För subprocess-fake-agenten |

Använd inte `com.agentclientprotocol:acp` eller `acp-ktor*`. De hör till Kotlin-SDK:n.

API-namn som skiljer sig från specens första skiss:

- `AcpClient.sync(transport)` returnerar `AcpSyncClient`.
- `.sessionUpdateHandler(SessionNotification -> …)`, inte `sessionUpdateConsumer`.
- `.requestPermissionHandler(RequestPermissionRequest -> RequestPermissionResponse)`.
- `AcpSchema.McpServerHttp(name, url, headers, _meta)` och `AcpSchema.HttpHeader(name, value, _meta)`.
- `NewSessionRequest(cwd, mcpServers, additionalDirectories, _meta)`. `_meta` används för att begränsa tools i Claude Code.
- SDK-repot har ingen `examples/`-katalog, så README och källkoden är referensen.

## Agenter (externa, installeras av utvecklaren)

| Agent | Version | Paket / kommando | Krav |
| --- | --- | --- | --- |
| Claude Code via ACP | 0.106.0 | npm `@agentclientprotocol/claude-agent-acp`, binär `claude-agent-acp` | Node ≥ 22, Claude Code-prenumeration eller `ANTHROPIC_API_KEY` |
| Gemini CLI (profil `gemini`) | 0.68.0 | npm `@google/gemini-cli`, `gemini --acp` | Node ≥ 20. `--experimental-acp` är deprecated. |

`claude-agent-acp` annonserar `mcpCapabilities: {http: true, sse: true}` och tar `_meta.claudeCode.options` (`tools`, `allowedTools`, `disallowedTools`) i `session/new`. Både detta och själva HTTP-anslutningen verifieras i spiken (steg 3). Detta kommer från källkoden och har inte provats.

## Frontend

Statisk HTML och vanilla JS i `src/main/resources/static`, utan byggsteg. Eventuellt `marked` från CDN för markdown i agentbubblorna.

## Test

| Verktyg | Användning |
| --- | --- |
| JUnit 5, Spring Boot Test | Alla tester. Testnamn följer `should_X_when_Y`. TDD: testet skrivs först. |
| MCP Java SDK-klient | `McpEndpointIT` mot `/mcp` |
| `acp-test` (`InMemoryTransportPair`) | Snabba tester av streaming, permission (godkänd, avböjd, timeout), cancel och felmappning |
| Fake-agent som subprocess (`acp-agent-support`) | Bara processlivscykeln: start, en process som dör mitt i en tur, inga kvarlämnade processer |

Inga mockar av ACP-SDK:n, och ingen riktig modell i automatiska tester.

## Motiveringar för mindre val

Dessa val är lätta att ändra och har därför ingen egen ADR.

### Java 25

POC:n kör blockerande ACP- och stdio-anrop på virtuella trådar. Från Java 24 (JEP 491) låser `synchronized` inte längre den virtuella tråden vid sin bärartråd. Det är därför inte längre ett problem om biblioteken internt synkroniserar runt I/O. Java 25 är aktuell LTS.

### Rå WebSocket utan STOMP

Protokollet är ett fåtal JSON-meddelanden mellan en flik och dess Chatt. STOMP:s topics och broker tillför inget, utom för uppgiftshändelserna, som en enkel `TaskBroadcaster` sköter.

### Vanilla JS utan byggsteg

POC:n ska visa backendkedjan. Ett frontend-ramverk skulle bara lägga till ett byggsteg och fler beroenden.

### Spring-events för uppgiftshändelser

`TaskService` publicerar via porten `ITaskEventPublisher`, och en adapter mappar den mot `ApplicationEventPublisher`. `TaskBroadcaster` lyssnar med `@EventListener`. Det räcker inom en process och kan bytas ut bakom porten.
