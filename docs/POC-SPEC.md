# POC-specifikation: Spring Boot 4 + ACP-agent + egen MCP-server

Nov 12, 2026 · @Joel

> **Ändringslogg 2026-11-12.** Specen är nu implementationsguide. Vad och varför (FR/NFR/DR med ID:n, acceptanskriterier) står i [`PRD.md`](./PRD.md), verifierade versioner i [`TECH-STACK.md`](./TECH-STACK.md), arkitekturbeslut i [`adr/`](./adr/) och begrepp i [`GLOSSARY.md`](../GLOSSARY.md). Ändrat i den här specen:
> - Java 21 → 25.
> - Versionerna låsta efter kontroll.
> - Paketet heter `se.joelabs.acppoc`.
> - Hexagonal struktur för uppgiftsdomänen ([ADR-0004](./adr/0004-hexagonal-only-where-domain-logic-exists.md)).
> - `TaskChangedEvent` är ersatt av `TaskCreated`, `TaskCompleted` och `TaskDeleted`, som publiceras av `TaskService`.
> - SDK:ns riktiga API-namn används.
> - Verktygsbegränsning och förgodkända tools via `_meta.claudeCode.options` ([ADR-0005](./adr/0005-agent-security-boundary.md)).
> - Utvecklartoken via `POC_MCP_DEV_TOKEN`.
> - Gemini körs med `--acp`.
> - Fake-agenten är uppdelad i in-memory och subprocess.
> - De öppna frågorna är besvarade.

## Syfte och omfattning

POC:n ska visa att en Spring Boot 4-app kan starta en extern ACP-agent som subprocess, låta användaren chatta med den via en webbfrontend, och att agenten anropar tools som samma app exponerar via MCP. Hela kedjan frontend → backend → ACP-agent → backendens MCP-server ska fungera från början till slut.

**Ingår**

- En Spring Boot-applikation (en JAR, en process) som innehåller MCP-server, ACP-klient och frontend.
- En extern ACP-agent: Claude Code via adaptern `claude-agent-acp`. Agentkommandot ska vara konfigurerbart så att t.ex. Gemini CLI kan provas utan kodändring.
- Tre till fyra enkla tools mot en in-memory-domän (uppgiftslista).
- Streamad chatt i webbläsaren med visning av tool-anrop och godkännandedialog för permissions.

**Ingår inte**

- Inloggning och flera användare. POC:n körs lokalt av en utvecklare.
- Persistens. Uppgifter och chatthistorik lever i minnet och försvinner vid omstart.
- Egen agentloop med Spring AI eller LangChain4j (variant B).
- Produktionsdrift, containerisering, horisontell skalning.

## Arkitektur och dataflöde

Backend har två roller mot samma agent. Den är ACP-klient som styr agenten över stdio, och MCP-server som agenten anropar över HTTP.

&#91;embedded content: arkitektur · 3 delar i backend, 1 extern agent\]

En tur från användarens meddelande till svar:

1. Frontend skickar `prompt` över WebSocket.
2. `AgentSession` skickar `session/prompt` till agentprocessen över stdio.
3. Agenten anropar modellen via Anthropic API och bestämmer sig för att använda ett tool.
4. Agenten skickar `session/update` (`tool_call`) till backend och, vid behov, `session/request_permission`, som går vidare till användaren.
5. Agenten anropar `tools/call` på `http://127.0.0.1:8080/mcp` med chattens token. `TaskTools` utför anropet.
6. Agenten streamar svarstext som `agent_message_chunk`. Backend översätter till `agent_text`.
7. `session/prompt` returnerar med `stopReason`. Backend skickar `turn_end`.

## Teknikval och versioner

Allt körs på Java 25 och Spring Boot 4.1, och Spring AI används bara för MCP-servern. Exakta versioner, koordinater och motiveringar finns i [`TECH-STACK.md`](./TECH-STACK.md). Den filen gäller före tabellen nedan.

| Komponent | Val | Koordinat / paket | Kommentar |
| --- | --- | --- | --- |
| Språk | Java 25 | – | Virtuella trådar för blockerande ACP-anrop, utan pinning vid `synchronized` (JEP 491) |
| Ramverk | Spring Boot 4.1.2 | `spring-boot-starter-parent` | Maven som byggverktyg |
| MCP-server | Spring AI 2.0.2 | `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` via `spring-ai-bom` | Streamable HTTP |
| ACP-klient | ACP Java SDK 0.81.0 | `com.agentclientprotocol:acp-core` + `acp-json-jackson3`; test: `acp-test`, `acp-agent-support` | `acp-java-sdk` är bara parent-POM |
| WebSocket | Spring WebSocket | `spring-boot-starter-websocket` | Rå `TextWebSocketHandler`, ingen STOMP |
| Agent | Claude Code via ACP-adapter 0.106.0 | npm `@agentclientprotocol/claude-agent-acp`, binär `claude-agent-acp` | Kräver Node ≥ 22 och inloggad Claude Code eller `ANTHROPIC_API_KEY` |
| Frontend | Statisk HTML + vanilla JS | `src/main/resources/static` | Inget byggsteg; ev. `marked` från CDN för markdown |
| Test | JUnit 5, Spring Boot Test, MCP Java SDK-klient 2.1.0 | – | Integrationstest mot `/mcp` |

Adapterns `initialize` annonserar `mcpCapabilities: {http: true, sse: true}`. Den tar också emot `_meta.claudeCode.options` (`tools`, `allowedTools`, `disallowedTools`) i `session/new`. Båda uppgifterna kommer från källkoden och verifieras i steg 3.

## Funktionella krav

Krav, domänregler och icke-funktionella krav med ID:n (FR-1–FR-15, DR-1–DR-6, NFR-1–NFR-9) står i [`PRD.md`](./PRD.md). Implementation, tester och commits hänvisar till de ID:na.

## MCP-server: tools i POC:n

MCP-servern exponerar fem tools mot en in-memory-uppgiftslista. De visar läsning, skrivning och en destruktiv operation, så att permission-flödet kan provas. Varje tool är en metod med `@McpTool` på en Spring-böna. Beskrivningarna skrivs för modellen, och kvaliteten på dem avgör om agenten väljer rätt tool.

| Tool | Parametrar | Returnerar | Typ |
| --- | --- | --- | --- |
| `list_tasks` | `status` (valfri: `OPEN`, `DONE`) | Lista med `id`, `title`, `status`, `createdAt` | Läsning |
| `create_task` | `title` (obligatorisk, 1–200 tecken) | Den skapade uppgiften | Skrivning |
| `complete_task` | `id` | Uppdaterad uppgift, eller fel om id saknas | Skrivning |
| `delete_task` | `id` | Bekräftelse | Destruktiv (annoteras `destructiveHint = true`) |

```java
// Driving adapter (ports & adapters): översätter MCP-anrop till application-tjänsten.
@Component
class TaskTools {
    private final TaskService tasks;

    @McpTool(name = "create_task",
             description = "Skapar en ny uppgift i användarens uppgiftslista och returnerar den.")
    TaskDto createTask(@McpToolParam(description = "Kort rubrik, 1–200 tecken") String title) {
        return tasks.create(title);
    }
}
```

Domänregler (DR-1–DR-7 i [PRD](./PRD.md)):

- Id:n är löpnummer.
- Titeln trimmas och måste sedan vara 1–200 tecken. Dubblettitlar är tillåtna.
- `rename_task` byter titel oavsett status. Oförändrad titel efter trim ger ingen händelse (DR-7).
- `complete_task` på en slutförd uppgift lyckas utan händelse, och en slutförd uppgift kan inte öppnas igen.

Krav på MCP-servern:

- Transport: Streamable HTTP (`spring.ai.mcp.server.protocol=STREAMABLE`), endpoint `/mcp`. SSE används inte eftersom det är deprecated sedan Spring AI 2.0.0 ([docs](https://docs.spring.io/spring-ai/reference/2.1/api/mcp/mcp-server-boot-starter-docs.html)).
- Servertyp `SYNC`. Tools är små och snabba.
- Valideringsfel returneras som MCP-toolfel med ett läsbart meddelande, inte som exception-stacktrace.
- Varje anrop loggas på INFO med toolnamn, chatt-id (från token, se Säkerhet) och duration.
- Aggregatet `Task` skapar en domänhändelse vid varje lyckad ändring: `TaskCreated`, `TaskRenamed`, `TaskCompleted` eller `TaskDeleted`. De ingår i sealed interface `TaskChanged`. `TaskService` publicerar händelsen via porten `ITaskEventPublisher`, och `SpringTaskEventPublisher` mappar porten mot `ApplicationEventPublisher`. Repositoryt publicerar aldrig, och tools vet inget om WebSocket ([ADR-0004](./adr/0004-hexagonal-only-where-domain-logic-exists.md)).

## ACP-integration

Backend är ACP-klient. Varje chatt får en egen agentprocess och en egen ACP-session ([ADR-0002](./adr/0002-one-agent-process-per-chat.md)). Max antal samtidiga chattar är konfigurerbart (standard 3).

**Komponenter**

| Klass | Ansvar |
| --- | --- |
| `AgentProperties` | `@ConfigurationProperties("poc.agent")`: kommando, argument, miljövariabler, tillåtna och förgodkända tools, timeouts, max antal sessioner |
| `IAgentSession` | Port mot en agent: `start()`, `prompt(text)`, `cancel()`, `resolvePermission(requestId, optionId)`, `close()`. Resten av appen ser bara denna. |
| `AcpAgentSession` | Adapter som implementerar `IAgentSession`. Kapslar en agentprocess + `AcpSyncClient` + ACP-`sessionId`. |
| `AgentSessionFactory` | Factory Method: skapar `AcpAgentSession`. Bygger `AgentParameters`, transport och klient, och kör `initialize` + `session/new`. |
| `AgentSessionRegistry` | Mappar WebSocket-session-id → `IAgentSession`. Upprätthåller maxgränsen och städar vid nedstängning (`@PreDestroy`). |
| `AcpEventMapper` | Översätter ACP-`SessionUpdate` till frontendens JSON-händelser (se Frontend) |
| `McpTokenService` | Skapar en slumpad token per chatt och slår upp chatt-id från token |

**Sessionslivscykel**

1. WebSocket öppnas. `AgentSessionRegistry` kontrollerar maxgränsen och anropar fabriken.
2. Fabriken skapar en arbetskatalog `${poc.agent.workspace-root}/<chatId>` och en MCP-token.
3. Agentprocessen startas med `StdioAcpClientTransport` (stdio, JSON-RPC). Processens stderr läses i en egen tråd och loggas på DEBUG.
4. `initialize` anropas. Svarets `agentCapabilities.mcpCapabilities.http` kontrolleras (FR-11).
5. `session/new` anropas med följande:
   - `cwd` = arbetskatalogen.
   - En HTTP-MCP-server med `name` = `poc-tools`, `url` = `http://127.0.0.1:${server.port}/mcp` och headern `Authorization: Bearer <token>`.
   - `_meta.claudeCode.options`, med `tools` (tillåtna) och `allowedTools` (förgodkända) från `poc.agent` (FR-14, FR-15, [ADR-0005](./adr/0005-agent-security-boundary.md)).
6. Frontend får `session_ready`.
7. Varje `prompt` körs på en virtuell tråd. `session/update`-notiser strömmar under tiden via `sessionUpdateHandler`. När `prompt` returnerar skickas `turn_end` med `stopReason`.
8. WebSocket stängs: `session/cancel` om en tur pågår, sedan `client.close()`. Lever processen efter 5 s dödas den med `destroyForcibly()`. Arbetskatalogen tas bort.

**Skiss med SDK:ns API (acp-core 0.81.0)**

```java
var params = AgentParameters.builder(props.command())
        .args(props.args())          // kontrollera exakt API i SDK:n
        .env(props.env())
        .build();
var transport = new StdioAcpClientTransport(params);
AcpSyncClient client = AcpClient.sync(transport)
        .sessionUpdateHandler(n -> sink.accept(mapper.map(n.update())))
        .requestPermissionHandler(req -> permissions.await(req, props.permissionTimeout()))
        .build();
client.initialize();
var meta = Map.of("claudeCode", Map.of("options", Map.of(
        "tools", props.tools(),                 // mcp__poc-tools__*, TodoWrite
        "allowedTools", props.preApprovedTools())));
var session = client.newSession(new NewSessionRequest(workspace.toString(),
        List.of(new McpServerHttp("poc-tools", mcpUrl,
                List.of(new HttpHeader("Authorization", "Bearer " + token, null)), null)),
        List.of(), meta));
var response = client.prompt(new PromptRequest(session.sessionId(),
        List.of(new TextContent(userText))));
```

Följande är verifierat i SDK:ns README och källkod (`AcpSchema`):

- `AcpClient.sync`, `sessionUpdateHandler` och `requestPermissionHandler`.
- `McpServerHttp(name, url, headers, _meta)` och `HttpHeader(name, value, _meta)`.
- `NewSessionRequest(cwd, mcpServers, additionalDirectories, _meta)`.

Byggmetoden för `AgentParameters` och konstruktorerna för `PromptRequest` och `TextContent` ska kontrolleras mot källkoden. SDK-repot har ingen `examples/`-katalog.

**Permissions**

- Bara tools som inte är förgodkända ger en `session/request_permission`. I standardkonfigurationen gäller det bara `delete_task` (FR-15).
- Hanteraren skickar `permission_request` till frontend med ett `requestId` och agentens `options` (t.ex. allow once, allow always, reject).
- Den väntar på en `CompletableFuture` som `resolvePermission` fullbordar. Vid timeout eller stängd WebSocket svarar den med avbrutet utfall (`cancelled`).
- Backend svarar aldrig själv på en förfrågan (NFR-7). Vilka tools som är förgodkända styrs av konfiguration, inte av kod.
- POC:n sparar inga "allow always"-val själv. Det hanteras av agenten.

**Klientkapabiliteter**

I `initialize` annonserar backend inga `fs`- eller `terminal`-kapabiliteter. Backend behöver därför inte implementera `fs/read_text_file` eller `terminal/*`. Agentens egna fil- och skaltools är avstängda via `tools` (FR-14).

## Frontend och WebSocket-protokoll

Frontend är en enda sida (`index.html`, `app.js`, `app.css`) som pratar JSON över en WebSocket på `/ws/chat`. Frontend känner inte till ACP. Alla ACP-detaljer stannar i `AcpEventMapper`.

**Layout**

- Meddelandelista: användarbubblor, agentbubblor (markdown), tool-kort (namn, status, kollapsbart resultat) och ett plankort.
- Inmatningsfält med Skicka och Stopp. Skicka är låst medan en tur pågår.
- Modal för permission-förfrågningar med en knapp per alternativ.
- Statusrad: ansluten, agent startar, redo, fel.
- Uppgiftspanel bredvid chatten: titel och status per uppgift, öppna överst. Panelen är redigerbar (FR-16): inmatningsfält för ny Uppgift, checkbox, penn-knapp och borttagningsknapp per rad. Den anropar REST (se nedan) och uppdateras bara av `task_changed`. En rad som just skapats eller ändrats markeras kort (ca 2 s), och en borttagen rad tonas ut.

**REST för panelen (FR-17, NFR-10)**

| Anrop | Body | Svar |
| --- | --- | --- |
| `POST /api/tasks` | `{"title"}` | 201 + Uppgiften |
| `PATCH /api/tasks/{id}` | `{"title"}` | 200 + Uppgiften |
| `POST /api/tasks/{id}/complete` | – | 200 + Uppgiften |
| `DELETE /api/tasks/{id}` | – | 204 |

Fel ger `{"error"}` med 400 (ogiltig titel), 403 (fel eller saknat `Origin`, eller annan `Content-Type`) eller 404 (okänt id). `RestOriginFilter` kontrollerar `Origin` mot `poc.web.allowed-origins` för alla ändrande anrop.

**Klient → server**

| `type` | Fält | Betydelse |
| --- | --- | --- |
| `prompt` | `text` | Skicka användarmeddelande |
| `cancel` | – | Avbryt pågående tur |
| `permission_response` | `requestId`, `optionId` (null = avböj) | Svar på permission-förfrågan |

**Server → klient**

| `type` | Fält | Källa i ACP |
| --- | --- | --- |
| `session_ready` | `chatId`, `agentName`, `mcpHttpSupported` | `initialize` + `session/new` |
| `agent_text` | `text` | `agent_message_chunk` |
| `agent_thought` | `text` | `agent_thought_chunk` (visas nedtonat) |
| `tool_call` | `toolCallId`, `title`, `kind`, `status` | `tool_call` |
| `tool_call_update` | `toolCallId`, `status`, `content` (text, trunkerad till 2 000 tecken) | `tool_call_update` |
| `plan` | `entries[]` med `content`, `status` | `plan` |
| `permission_request` | `requestId`, `title`, `options[]` med `optionId`, `name`, `kind` | `session/request_permission` |
| `turn_end` | `stopReason` | Svaret på `session/prompt` |
| `error` | `message` | Undantag i backend eller agentprocess som dör |
| `tasks_snapshot` | `tasks[]` med `id`, `title`, `status`, `createdAt` | Inte ACP: `TaskService`, skickas direkt när WebSocket öppnas |
| `task_changed` | `change` (`CREATED`, `UPDATED`, `DELETED`), `task` | Inte ACP: `TaskCreated` → `CREATED`, `TaskRenamed` och `TaskCompleted` → `UPDATED`, `TaskDeleted` → `DELETED` |

Övriga `session/update`-varianter loggas på DEBUG och ignoreras i POC:n. Det gäller t.ex. `available_commands_update` och `current_mode_update`.

**Liveuppdatering av uppgifter**

Uppgiftslistan är gemensam för alla chattar. Därför skickas uppgiftshändelser till alla öppna WebSocket-sessioner, inte bara till chatten vars agent gjorde ändringen. En `TaskBroadcaster` (Observer) lyssnar på `TaskChanged` med `@EventListener` och skickar `task_changed` till varje session i ett register. Skrivningar till en WebSocket-session synkroniseras (eller sker via `ConcurrentWebSocketSessionDecorator`), eftersom agentströmmen och uppgiftshändelser kan skrivas samtidigt.

Frontend uppdaterar panelen från `task_changed` och litar inte på agentens text. Vid återanslutning ersätter ett nytt `tasks_snapshot` hela panelen.

## Konfiguration

All konfiguration ligger i `application.yml`. Hemligheter läses från miljövariabler och checkas aldrig in.

```yaml
server:
  port: 8080
  address: 127.0.0.1          # POC:n lyssnar bara lokalt

spring:
  threads.virtual.enabled: true
  ai.mcp.server:
    name: poc-tools
    version: 0.1.0
    type: SYNC
    protocol: STREAMABLE

poc:
  agent:
    command: claude-agent-acp   # alt: npx, args: ["-y", "@agentclientprotocol/claude-agent-acp"]
    args: []
    env:
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY:}   # tom = inloggad Claude Code-prenumeration
    tools:                      # allt agenten får använda (FR-14)
      - mcp__poc-tools__*
      - TodoWrite
    pre-approved-tools:         # inga Permission-förfrågningar (FR-15)
      - mcp__poc-tools__list_tasks
      - mcp__poc-tools__create_task
      - mcp__poc-tools__complete_task
      - TodoWrite
    workspace-root: ${java.io.tmpdir}/acp-poc
    max-sessions: 3
    startup-timeout: 30s
    permission-timeout: 120s
    shutdown-grace: 5s
  mcp:
    public-url: http://127.0.0.1:${server.port}/mcp
    dev-token: ${POC_MCP_DEV_TOKEN:}   # tom = utvecklartoken avstängd

logging.level:
  se.joelabs.acppoc: DEBUG
```

Profilen `gemini` (`application-gemini.yml`) sätter `command: gemini` och `args: ["--acp"]` (Gemini CLI 0.68.0; `--experimental-acp` är deprecated). Det visar att agenten kan bytas utan kodändring (FR-12). Gemini ignorerar `_meta.claudeCode`, så `tools` har ingen effekt där. Det är en känd lucka ([ADR-0005](./adr/0005-agent-security-boundary.md)).

## Säkerhet

Agenten är en kodagent med egna tools för filer och skal. Därför är den största risken i POC:n inte MCP-servern, utan vad agenten gör på utvecklarens maskin ([ADR-0005](./adr/0005-agent-security-boundary.md)).

- **Nätverk:** Appen binder till `127.0.0.1`. Inget exponeras utanför maskinen.
- **MCP-token:** Ett `OncePerRequestFilter` på `/mcp` kräver `Authorization: Bearer <token>`.
  - Token är 32 slumpade byte (Base64URL), en per chatt, och ogiltigförklaras när chatten stängs.
  - Filtret lägger chatt-id i en request-attribut, så att tools och loggning vet vilken chatt anropet tillhör.
  - Utvecklartoken för MCP Inspector är aktiv bara om `POC_MCP_DEV_TOKEN` är satt. Anrop med den loggas med chatt-id `dev`.
- **Verktyg:** Med Claude Code får agenten bara använda `mcp__poc-tools__*` och `TodoWrite`. Det sätts via `_meta.claudeCode.options.tools`.
- **Arbetskatalog:** Agentens `cwd` är en tom katalog per chatt under `workspace-root`, aldrig projektets källkod eller hemkatalogen.
- **Permissions:** Backend svarar aldrig själv på en `session/request_permission`. Alla förfrågningar går till användaren. Förgodkända tools konfigureras i `poc.agent.pre-approved-tools`, och destruktiva tools förgodkänns aldrig.
- **Hemligheter:** `ANTHROPIC_API_KEY` kommer från miljön och skickas bara till agentprocessen. Den loggas aldrig, inte heller på TRACE.
- **Process:** Agentprocessen ärver inte hela förälderns miljö. Bara `PATH`, `HOME` och de variabler som anges i `poc.agent.env` skickas vidare. `HOME` behövs för att en inloggad Claude Code-prenumeration ska fungera.
- **WebSocket:** Origin-kontroll som bara tillåter `http://localhost:8080` och `http://127.0.0.1:8080`.

## Projektstruktur

Ett Maven-projekt med en modul. Uppgiftsdomänen är hexagonal, och ACP-delen är en port med en adapter ([ADR-0004](./adr/0004-hexagonal-only-where-domain-logic-exists.md)).

```text
acp-todo-poc/
├── pom.xml
├── CLAUDE.md                       # kort: bygg-/testkommandon, länk till PRD och denna spec
├── README.md                       # förutsättningar, start, felsökning
├── GLOSSARY.md
├── docs/                           # PRD.md, TECH-STACK.md, adr/, denna spec
└── src/
    ├── main/java/se/joelabs/acppoc/
    │   ├── AcpPocApplication.java
    │   ├── task/
    │   │   ├── domain/             # Task (Aggregate Root), TaskId, TaskStatus,
    │   │   │                       # TaskChanged (sealed) + TaskCreated/TaskCompleted/TaskDeleted,
    │   │   │                       # ITaskRepository, ITaskEventPublisher
    │   │   ├── application/        # TaskService, TaskDto
    │   │   └── adapter/
    │   │       ├── mcp/            # TaskTools
    │   │       ├── memory/         # InMemoryTaskRepository
    │   │       └── spring/         # SpringTaskEventPublisher
    │   ├── mcp/                    # McpTokenService, McpAuthFilter
    │   ├── agent/                  # IAgentSession, AcpAgentSession, AgentProperties,
    │   │                           # AgentSessionFactory, AgentSessionRegistry, PermissionBroker
    │   ├── events/                 # AcpEventMapper, UiEvent (sealed interface + records)
    │   └── web/                    # WebSocketConfig, ChatWebSocketHandler, TaskBroadcaster
    ├── main/resources/
    │   ├── application.yml
    │   ├── application-gemini.yml
    │   └── static/                 # index.html, app.js, app.css
    └── test/java/se/joelabs/acppoc/
        ├── task/domain/TaskTest.java
        ├── task/application/TaskServiceTest.java
        ├── mcp/McpEndpointIT.java
        ├── events/AcpEventMapperTest.java
        ├── agent/AgentSessionTest.java     # InMemoryTransportPair (acp-test)
        └── agent/FakeAgentProcessIT.java   # fake-agent som subprocess (acp-agent-support)
```

`UiEvent` modelleras som en `sealed interface` med en `record` per `type` i protokolltabellen. Jackson 3 serialiserar dem med ett `type`-fält.

## Implementationsplan för Claude Code

Bygg i sju steg, ett steg per commit (Conventional Commits). Arbeta TDD: testet först. Gå inte vidare förrän stegets "Klart när" är uppfyllt och `./mvnw verify` är grönt. Steg 3 är en spike. Om den visar att adaptern inte ansluter till HTTP-MCP-servrar ska Claude Code stanna och rapportera, inte bygga runt problemet.

1. **Skelett.** Spring Boot 4.1.2-projekt med Java 25, Maven wrapper och beroenden enligt [`TECH-STACK.md`](./TECH-STACK.md). Dessutom `application.yml`, `CLAUDE.md` och tom `index.html`.
   - Klart när: appen startar, `GET /` svarar 200 och MCP-autokonfigurationen från Spring AI 2.0.2 startar. Startar den inte: sänk Boot enligt TECH-STACK och dokumentera.
2. **Uppgiftsdomän och MCP-server.** `task/domain`, `TaskService`, adaptrarna, `TaskTools` med fyra tools, `McpTokenService` och `McpAuthFilter`.
   - Klart när: domäntesterna täcker DR-1–DR-6. `McpEndpointIT` listar fyra tools och anropar `create_task` via MCP Java SDK-klient med token. Ett anrop utan token ger 401.
3. **Spike: ACP mot riktig agent.** Ett `CommandLineRunner` bakom profilen `spike` gör följande:
   1. Startar `claude-agent-acp` och loggar `initialize`-svaret.
   2. Skapar en session med HTTP-MCP-servern och `_meta.claudeCode.options`.
   3. Promptar "Skapa en uppgift som heter Testa POC, lista sedan alla uppgifter och ta bort den".
   4. Promptar "Kör `ls` i skalet".
   - Klart när:
     - Loggen visar att `create_task` och `list_tasks` anropats på `/mcp` med rätt token, utan Permission-förfrågan.
     - `delete_task` gav en Permission-förfrågan.
     - Agenten saknade ett skaltool.
     - Resultatet är dokumenterat i README.
4. **AgentSession och registry.** Livscykeln enligt ACP-integration, inklusive timeouts, städning och maxgräns.
   - `AgentSessionTest` använder `InMemoryTransportPair`, för streaming, permission (godkänd, avböjd, timeout), cancel och felmappning.
   - `FakeAgentProcessIT` startar en minimal agent skriven med `acp-agent-support` som subprocess, bara för processlivscykeln.
   - Klart när: testerna visar start, prompt med streamade chunks, permission-runda, cancel och städning utan kvarlämnade processer.
5. **Event-mappning och WebSocket.** `UiEvent`, `AcpEventMapper`, `ChatWebSocketHandler`, `PermissionBroker` och `TaskBroadcaster`.
   - Klart när:
     - `AcpEventMapperTest` täcker varje rad i protokolltabellen.
     - Ett test visar att en uppgiftshändelse når alla öppna WebSocket-sessioner som `task_changed`.
     - Ett WebSocket-test mot fake-agenten får `session_ready` → `agent_text` → `turn_end`.
6. **Frontend.** Chattvy, uppgiftspanel med liveuppdatering, tool-kort, plankort, permission-modal, stoppknapp och statusrad.
   - Klart när: manuell körning enligt acceptanskriterierna i [PRD](./PRD.md) fungerar i Chrome och Firefox.
7. **Finputs.** README med förutsättningar och felsökning:
   - Java 25.
   - Node ≥ 22.
   - `npm i -g @agentclientprotocol/claude-agent-acp`.
   - Inloggad Claude Code eller API-nyckel.
   - Profilen `gemini`.
   - Klart när: en ny utvecklare kan starta POC:n enbart med README.

**Instruktioner till Claude Code**

- Läs ACP Java SDK:ns README och källkod (`AcpSchema`, `acp-test`, `acp-agent-support`) innan steg 3 och 4. Använd SDK:ns faktiska klass- och metodnamn, inte skissens.
- Läs ACP-specens sidor om initialization, session setup, prompt turn, tool calls och permissions på agentclientprotocol.com när du är osäker på ett meddelandes form.
- Inga mockar av ACP-SDK:n i tester. Använd in-memory-transporten eller fake-agenten.
- Testnamn följer `should_[expected]_when_[condition]`.
- Fråga innan beroenden utöver de i [`TECH-STACK.md`](./TECH-STACK.md) läggs till.

## Acceptanskriterier och tester

Demoscenariot står i [PRD](./PRD.md#acceptanskriterier). POC:n är klar när det fungerar mot riktig `claude-agent-acp` och följande automatiska tester är gröna.

**Automatiska tester**

- [ ] `TaskTest` och `TaskServiceTest` (DR-1–DR-6):
  - skapa, lista och filtrera
  - slutför, och slutför igen utan händelse
  - ta bort, och okänt id
  - trimning, och tom eller för lång titel
  - exakt en händelse per lyckad ändring
- [ ] `McpEndpointIT`:
  - `tools/list` ger fyra tools och `tools/call` fungerar
  - 401 utan token eller med fel token
  - token slutar gälla när chatten stängs
  - utvecklartoken avvisas när `POC_MCP_DEV_TOKEN` är tom
- [ ] `AcpEventMapperTest`: ett testfall per händelsetyp i protokolltabellen, plus att okända uppdateringar ignoreras.
- [ ] `AgentSessionTest`:
  - streamad prompt
  - permission godkänd, avböjd och timeout
  - cancel
  - `_meta.claudeCode.options` skickas i `session/new`
- [ ] `FakeAgentProcessIT`: start, en process som dör mitt i en tur ger `error`, ingen kvarlämnad process efter stängning.
- [ ] Maxgräns: en fjärde samtidig chatt får `error` med tydligt meddelande.

## Risker

Den största risken är att agenten tyst ignorerar `mcpServers`. Därför ligger spiken tidigt, i steg 3.

| Risk | Konsekvens | Åtgärd |
| --- | --- | --- |
| Agenten annonserar MCP-stöd men ansluter aldrig. Det är rapporterat för Cursors ACP-agent ([forum](https://forum.cursor.com/t/acp-agent-silently-ignores-mcpservers-in-session-new/153623)). | Kärnflödet fungerar inte | Spike i steg 3. Reserv: stdio-MCP-server via en tunn bryggprocess som proxar till `/mcp` ([ADR-0003](./adr/0003-mcp-over-http-with-per-chat-token.md)) |
| `_meta.claudeCode.options` fungerar inte som källkoden antyder | Agenten har sina inbyggda tools, och demon drunknar i dialoger | Spike i steg 3. Annars gäller grundskyddet i ADR-0005 |
| ACP Java SDK är version 0.x och API:t ändras | Kod bryts vid uppgradering | Versionen är låst (0.81.0). SDK:n kapslas bakom `IAgentSession`. |
| Uppstart av en agentprocess per chatt tar flera sekunder | Långsam första interaktion | Acceptabelt i POC ([ADR-0002](./adr/0002-one-agent-process-per-chat.md)) |
| Kostnad för API-anrop under utveckling | Oväntad faktura | Fake-agent i alla automatiska tester. Riktig agent bara i spike och demo. |

**Besvarade frågor (2026-11-12)**

- *Kan agenten begränsas till MCP-tools?* Ja enligt adapterns källkod, via `_meta.claudeCode.options.tools`. Verifieras i spiken.
- *Vilken MCP-transport stöder adaptern?* HTTP och SSE enligt `initialize`. HTTP används.
- *API-nyckel eller prenumeration?* Båda, utan kodskillnad. Prenumeration är standard för demon.
- *Chatthistorik vid omladdning?* Nej. Omladdning ger en ny Chatt, och `session/load` ingår inte.
