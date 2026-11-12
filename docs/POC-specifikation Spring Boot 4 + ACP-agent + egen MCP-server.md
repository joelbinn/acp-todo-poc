# POC-specifikation: Spring Boot 4 + ACP-agent + egen MCP-server

Nov 12, 2026 · @Joel

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

Allt körs på Java 21 och Spring Boot 4.1, och Spring AI används bara för MCP-servern. Versionerna nedan var aktuella vid skrivandet. Claude Code ska kontrollera senaste releaseversion på Maven Central och npm innan de låses.

| Komponent | Val | Koordinat / paket | Kommentar |
| --- | --- | --- | --- |
| Språk | Java 21 | – | Virtuella trådar används för blockerande ACP-anrop |
| Ramverk | Spring Boot 4.1.x | `spring-boot-starter-parent` | Maven som byggverktyg |
| MCP-server | Spring AI 2.0.x | `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` via `spring-ai-bom` | [Spring AI 2.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/), Streamable HTTP |
| ACP-klient | ACP Java SDK | `com.agentclientprotocol:acp-core` (README) eller `acp-java-sdk` (Maven Central, 0.11.0 senast sedd) | [Java SDK](https://agentclientprotocol.com/libraries/java); kontrollera vilket artefaktnamn som gäller |
| WebSocket | Spring WebSocket | `spring-boot-starter-websocket` | Rå `TextWebSocketHandler`, ingen STOMP |
| Agent | Claude Code via ACP-adapter | npm `@agentclientprotocol/claude-agent-acp`, binär `claude-agent-acp` | Kräver Node.js och `ANTHROPIC_API_KEY` eller inloggad Claude Code |
| Frontend | Statisk HTML + vanilla JS | `src/main/resources/static` | Inget byggsteg; ev. `marked` från CDN för markdown |
| Test | JUnit 5, Spring Boot Test, MCP Java SDK-klient | – | Integrationstest mot `/mcp` |

Adaptern listar uttryckligen stöd för "client MCP servers", tool-anrop med permission-förfrågningar och TODO-listor ([README](https://cdn.jsdelivr.net/npm/@agentclientprotocol/claude-agent-acp@0.41.0/README.md)). Dess README anger inte vilka MCP-transporter som stöds. Det verifieras i steg 3 i implementationsplanen.

## Funktionella krav

Kraven har ID:n så att implementation, tester och commits kan hänvisa till dem.

| ID | Krav |
| --- | --- |
| FR-1 | Användaren öppnar `http://localhost:8080/` och får en chattvy. En ny chatt startar en ny ACP-session. |
| FR-2 | Backend startar agentprocessen, gör `initialize` och `session/new` med backendens MCP-server angiven som HTTP-server i `mcpServers`. |
| FR-3 | Användarens meddelande skickas som `session/prompt`. Agentens svar streamas till frontend i realtid (text-chunks). |
| FR-4 | Tool-anrop som agenten gör visas i chatten med namn, status (pågår, klar, fel) och kort resultat. |
| FR-5 | När agenten skickar `session/request_permission` visas en dialog med agentens alternativ. Användarens val skickas tillbaka. Uteblivet svar inom 120 s tolkas som avböjt. |
| FR-6 | Användaren kan avbryta pågående svar med en stoppknapp (`session/cancel`). |
| FR-7 | Agentens plan (`plan`-uppdateringar) visas som en lista som uppdateras på plats. |
| FR-8 | När en tur är klar visas `stopReason` diskret, och inmatningen låses upp. |
| FR-9 | Stängs webbläsarfliken (WebSocket stängs) avslutas sessionen och agentprocessen inom 10 s. |
| FR-10 | MCP-servern exponerar POC:ns tools på `/mcp` och kan anropas från valfri MCP-klient med giltig token, t.ex. MCP Inspector. |
| FR-11 | Om agenten inte annonserar `mcpCapabilities.http` loggas en varning och användaren får ett tydligt felmeddelande i chatten. |
| FR-12 | Agentkommando, argument och miljövariabler konfigureras i `application.yml`. |
| FR-13 | Frontend visar en uppgiftspanel med alla uppgifter. När en uppgift skapas, ändras eller tas bort, oavsett om det sker via agenten eller en annan MCP-klient, uppdateras panelen direkt via WebSocket i alla öppna flikar, utan omladdning. |

## MCP-server: tools i POC:n

MCP-servern exponerar fyra tools mot en in-memory-uppgiftslista. De visar läsning, skrivning och en destruktiv operation, så att permission-flödet kan provas. Varje tool är en metod med `@McpTool` på en Spring-böna. Beskrivningarna skrivs för modellen, och kvaliteten på dem avgör om agenten väljer rätt tool.

| Tool | Parametrar | Returnerar | Typ |
| --- | --- | --- | --- |
| `list_tasks` | `status` (valfri: `OPEN`, `DONE`) | Lista med `id`, `title`, `status`, `createdAt` | Läsning |
| `create_task` | `title` (obligatorisk, 1–200 tecken) | Den skapade uppgiften | Skrivning |
| `complete_task` | `id` | Uppdaterad uppgift, eller fel om id saknas | Skrivning |
| `delete_task` | `id` | Bekräftelse | Destruktiv (annoteras `destructiveHint = true`) |

```java
@Component
class TaskTools {
    private final TaskRepository repo; // in-memory, ConcurrentHashMap

    @McpTool(name = "create_task",
             description = "Skapar en ny uppgift i användarens uppgiftslista och returnerar den.")
    Task createTask(@McpToolParam(description = "Kort rubrik, 1–200 tecken") String title) {
        return repo.create(title);
    }
}
```

Krav på MCP-servern:

- Transport: Streamable HTTP (`spring.ai.mcp.server.protocol=STREAMABLE`), endpoint `/mcp`. SSE används inte eftersom det är deprecated sedan Spring AI 2.0.0 ([docs](https://docs.spring.io/spring-ai/reference/2.1/api/mcp/mcp-server-boot-starter-docs.html)).
- Servertyp `SYNC`. Tools är små och snabba.
- Valideringsfel returneras som MCP-toolfel med ett läsbart meddelande, inte som exception-stacktrace.
- Varje anrop loggas på INFO med toolnamn, chatt-id (från token, se Säkerhet) och duration.
- TaskRepository publicerar ett Spring-event, TaskChangedEvent(change, task) med change = CREATED, UPDATED eller DELETED, efter varje lyckad ändring. Tools vet inget om WebSocket.

## ACP-integration

Backend är ACP-klient. Varje chatt får en egen agentprocess och en egen ACP-session. Det ger enkel isolering och städning i en POC, på bekostnad av några sekunders starttid per chatt. Max antal samtidiga chattar är konfigurerbart (standard 3).

**Komponenter**

| Klass | Ansvar |
| --- | --- |
| `AgentProperties` | `@ConfigurationProperties("poc.agent")`: kommando, argument, miljövariabler, timeouts, max antal sessioner |
| `AgentSession` | Kapslar en agentprocess + `AcpSyncClient` + ACP-`sessionId`. Metoder: `start()`, `prompt(text)`, `cancel()`, `resolvePermission(requestId, optionId)`, `close()` |
| `AgentSessionFactory` | Skapar `AgentSession`: bygger `AgentParameters`, transport, klient, kör `initialize` + `session/new` |
| `AgentSessionRegistry` | Mappar WebSocket-session-id → `AgentSession`. Upprätthåller maxgränsen och städar vid nedstängning (`@PreDestroy`) |
| `AcpEventMapper` | Översätter ACP-`SessionUpdate` till frontendens JSON-händelser (se Frontend) |
| `McpTokenService` | Skapar en slumpad token per chatt och slår upp chatt-id från token |

**Sessionslivscykel**

1. WebSocket öppnas. `AgentSessionRegistry` kontrollerar maxgränsen och anropar fabriken.
2. Fabriken skapar en arbetskatalog `${poc.agent.workspace-root}/<chatId>` och en MCP-token.
3. Agentprocessen startas med `StdioAcpClientTransport` (stdio, JSON-RPC). Processens stderr läses i en egen tråd och loggas på DEBUG.
4. `initialize` anropas. Svarets `agentCapabilities.mcpCapabilities.http` kontrolleras (FR-11).
5. `session/new` anropas med `cwd` = arbetskatalogen och en HTTP-MCP-server: `name` = `poc-tools`, `url` = `http://127.0.0.1:${server.port}/mcp`, header `Authorization: Bearer <token>`.
6. Frontend får `session_ready`.
7. Varje `prompt` körs på en virtuell tråd. `session/update`-notiser strömmar under tiden via `sessionUpdateConsumer`. När `prompt` returnerar skickas `turn_end` med `stopReason`.
8. WebSocket stängs: `session/cancel` om en tur pågår, sedan `client.close()`. Lever processen efter 5 s dödas den med `destroyForcibly()`. Arbetskatalogen tas bort.

**Skiss baserad på SDK:ns exempel**

```java
var params = AgentParameters.builder(props.command())
        .args(props.args())          // kontrollera exakt API i SDK:n
        .env(props.env())
        .build();
var transport = new StdioAcpClientTransport(params);
AcpSyncClient client = AcpClient.sync(transport)
        .sessionUpdateConsumer(n -> sink.accept(mapper.map(n.update())))
        .requestPermissionHandler(req -> permissions.await(req, Duration.ofSeconds(120)))
        .build();
client.initialize();
var session = client.newSession(new NewSessionRequest(workspace.toString(),
        List.of(new McpServerHttp("poc-tools", mcpUrl,
                List.of(new HttpHeader("Authorization", "Bearer " + token))))));
var response = client.prompt(new PromptRequest(session.sessionId(),
        List.of(new TextContent(userText))));
```

`AgentParameters`, `StdioAcpClientTransport`, `AcpClient.sync`, `sessionUpdateConsumer`, `NewSessionRequest`, `PromptRequest` och `TextContent` finns i SDK:ns Hello World-exempel. Namnen på permission-hanteraren och HTTP-MCP-typerna (`McpServerHttp`, `HttpHeader`) är antaganden. Claude Code ska slå upp dem i `AcpSchema` och SDK:ns exempel innan implementationen.

**Permissions**

- Hanteraren skickar `permission_request` till frontend med ett `requestId` och agentens `options` (t.ex. allow once, allow always, reject).
- Den väntar på en `CompletableFuture` som `resolvePermission` fullbordar. Vid timeout eller stängd WebSocket svarar den med avbrutet utfall (`cancelled`).
- POC:n sparar inga "allow always"-val själv. Det hanteras av agenten.

**Klientkapabiliteter**

I `initialize` annonserar backend inga `fs`- eller `terminal`-kapabiliteter. Då måste agenten använda sina egna tools, och backend behöver inte implementera `fs/read_text_file` eller `terminal/*`.

## Frontend och WebSocket-protokoll

Frontend är en enda sida (`index.html`, `app.js`, `app.css`) som pratar JSON över en WebSocket på `/ws/chat`. Frontend känner inte till ACP. Alla ACP-detaljer stannar i `AcpEventMapper`.

**Layout**

- Meddelandelista: användarbubblor, agentbubblor (markdown), tool-kort (namn, status, kollapsbart resultat) och ett plankort.
- Inmatningsfält med Skicka och Stopp. Skicka är låst medan en tur pågår.
- Modal för permission-förfrågningar med en knapp per alternativ.
- Statusrad: ansluten, agent startar, redo, fel.
- Uppgiftspanel bredvid chatten: titel och status per uppgift, öppna överst. En rad som just skapats eller ändrats markeras kort (ca 2 s), och en borttagen rad tonas ut.

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
| tasks\_snapshot | tasks\[\] med id, title, status, createdAt | Inte ACP: TaskRepository, skickas direkt när WebSocket öppnas |
| task\_changed | change (CREATED, UPDATED, DELETED), task | Inte ACP: TaskChangedEvent från TaskRepository |

Övriga `session/update`-varianter loggas på DEBUG och ignoreras i POC:n. Det gäller t.ex. `available_commands_update` och `current_mode_update`.

**Liveuppdatering av uppgifter**

Uppgiftslistan är gemensam för alla chattar. Därför skickas uppgiftshändelser till alla öppna WebSocket-sessioner, inte bara till chatten vars agent gjorde ändringen. En `TaskBroadcaster` lyssnar på `TaskChangedEvent` med `@EventListener` och skickar `task_changed` till varje session i ett register. Skrivningar till en WebSocket-session synkroniseras (eller sker via `ConcurrentWebSocketSessionDecorator`), eftersom agentströmmen och uppgiftshändelser kan skrivas samtidigt.

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
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY:}
    workspace-root: ${java.io.tmpdir}/acp-poc
    max-sessions: 3
    startup-timeout: 30s
    permission-timeout: 120s
    shutdown-grace: 5s
  mcp:
    public-url: http://127.0.0.1:${server.port}/mcp

logging.level:
  se.example.acppoc: DEBUG
```

En Spring-profil `gemini` byter `poc.agent.command` mot Gemini CLI i ACP-läge. Det visar att agenten kan bytas utan kodändring (FR-12). Claude Code ska kontrollera aktuell flagga för Gemini CLI:s ACP-läge. SDK-exemplet använder `--experimental-acp`.

## Säkerhet

Agenten är en kodagent med egna tools för filer och skal. Därför är den största risken i POC:n inte MCP-servern, utan vad agenten gör på utvecklarens maskin.

- **Nätverk:** Appen binder till `127.0.0.1`. Inget exponeras utanför maskinen.
- **MCP-token:** Ett `OncePerRequestFilter` på `/mcp` kräver `Authorization: Bearer <token>`. Token är 32 slumpade byte (Base64URL), en per chatt, och ogiltigförklaras när chatten stängs. Filtret lägger chatt-id i en request-attribut så att tools och loggning vet vilken chatt anropet tillhör. En separat konfigurerbar utvecklartoken tillåter MCP Inspector.
- **Arbetskatalog:** Agentens `cwd` är en tom katalog per chatt under `workspace-root`, aldrig projektets källkod eller hemkatalogen.
- **Permissions:** Alla `session/request_permission` går till användaren. Backend godkänner aldrig något automatiskt.
- **Hemligheter:** `ANTHROPIC_API_KEY` kommer från miljön och skickas bara till agentprocessen. Den loggas aldrig, inte heller på TRACE.
- **Process:** Agentprocessen ärver inte hela förälderns miljö. Bara `PATH`, `HOME` och de variabler som anges i `poc.agent.env` skickas vidare.
- **WebSocket:** Origin-kontroll som bara tillåter `http://localhost:8080` och `http://127.0.0.1:8080`.

## Projektstruktur

Ett Maven-projekt med en modul, paketerat per funktion. Paketnamnet `se.example.acppoc` är en platshållare.

```text
acp-poc/
├── pom.xml
├── CLAUDE.md                       # kort: bygg-/testkommandon, länk till denna spec
├── README.md                       # förutsättningar, start, felsökning
└── src/
    ├── main/java/se/example/acppoc/
    │   ├── AcpPocApplication.java
    │   ├── tools/                  # TaskTools, Task, TaskRepository, TaskChangedEvent
    │   ├── mcp/                    # McpTokenService, McpAuthFilter
    │   ├── agent/                  # AgentProperties, AgentSession, AgentSessionFactory,
    │   │                           # AgentSessionRegistry, PermissionBroker
    │   ├── events/                 # AcpEventMapper, UiEvent (sealed interface + records)
    │   └── web/                    # WebSocketConfig, ChatWebSocketHandler, TaskBroadcaster
    ├── main/resources/
    │   ├── application.yml
    │   ├── application-gemini.yml
    │   └── static/                 # index.html, app.js, app.css
    └── test/java/se/example/acppoc/
        ├── tools/TaskToolsTest.java
        ├── mcp/McpEndpointIT.java
        ├── events/AcpEventMapperTest.java
        └── agent/FakeAgentIT.java  # ACP-agent i Java som ställer in för riktig agent
```

`UiEvent` modelleras som en `sealed interface` med en `record` per `type` i protokolltabellen. Jackson 3 serialiserar dem med ett `type`-fält.

## Implementationsplan för Claude Code

Bygg i sju steg, ett steg per commit. Gå inte vidare förrän stegets "Klart när" är uppfyllt och `./mvnw verify` är grönt. Steg 3 är en spike. Om den visar att adaptern inte ansluter till HTTP-MCP-servrar ska Claude Code stanna och rapportera, inte bygga runt problemet.

1. **Skelett.** Spring Boot 4.1-projekt med Maven wrapper, beroenden enligt Teknikval, `application.yml`, `CLAUDE.md` och tom `index.html`.
   - Klart när: appen startar och `GET /` svarar 200.
2. **MCP-server.** `TaskRepository`, `TaskTools` med fyra tools, `McpTokenService` och `McpAuthFilter`.
   - Klart när: `McpEndpointIT` listar fyra tools och anropar `create_task` via MCP Java SDK-klient med token. Ett anrop utan token ger 401.
3. **Spike: ACP mot riktig agent.** Ett `CommandLineRunner` bakom profilen `spike` startar `claude-agent-acp`, loggar `initialize`-svaret, skapar session med HTTP-MCP-servern och promptar "Skapa en uppgift som heter Testa POC och lista sedan alla uppgifter".
   - Klart när: loggen visar att `create_task` och `list_tasks` anropats på `/mcp` med rätt token. Resultatet dokumenteras i README.
4. **AgentSession och registry.** Livscykeln enligt ACP-integration, inklusive timeouts, städning och maxgräns. En `FakeAgentIT` startar en minimal ACP-agent skriven med SDK:ns agentmodul som subprocess (eller in-memory-transport om SDK:n har det).
   - Klart när: testerna visar start, prompt med streamade chunks, permission-runda, cancel och städning utan kvarlämnade processer.
5. **Event-mappning och WebSocket.** `UiEvent`, `AcpEventMapper`, `ChatWebSocketHandler` och `PermissionBroker`.
   - Klart när: `AcpEventMapperTest` täcker varje rad i protokolltabellen, ett test visar att en ändring i TaskRepository når alla öppna WebSocket-sessioner som task\_changed, och ett WebSocket-test mot fake-agenten får `session_ready` → `agent_text` → `turn_end`.
6. **Frontend.** Chattvy, uppgiftspanel med liveuppdatering, tool-kort, plankort, permission-modal, stoppknapp och statusrad.
   - Klart när: manuell körning enligt Acceptanskriterier fungerar i Chrome och Firefox.
7. **Finputs.** README med förutsättningar (Java 21, Node.js, `npm i -g @agentclientprotocol/claude-agent-acp`, API-nyckel), felsökning och profilen `gemini`.
   - Klart när: en ny utvecklare kan starta POC:n enbart med README.

**Instruktioner till Claude Code**

- Läs ACP Java SDK:ns README och `examples/` innan steg 3 och 4. Använd SDK:ns faktiska klass- och metodnamn, inte skissens.
- Läs ACP-specens sidor om initialization, session setup, prompt turn, tool calls och permissions på agentclientprotocol.com när du är osäker på ett meddelandes form.
- Inga mockar av ACP-SDK:n i tester. Använd fake-agenten.
- Fråga innan beroenden utöver de i Teknikval läggs till.

## Acceptanskriterier och tester

POC:n är klar när alla automatiska tester är gröna och demoscenariot nedan fungerar mot riktig `claude-agent-acp`.

**Automatiska tester**

- [ ] `TaskToolsTest`: skapa, lista, filtrera, slutför, ta bort, validering av tom och för lång titel.
- [ ] `McpEndpointIT`: `tools/list` ger fyra tools, `tools/call` fungerar, 401 utan eller med fel token, token slutar gälla när chatten stängs.
- [ ] `AcpEventMapperTest`: en testfall per händelsetyp i protokolltabellen, plus att okända uppdateringar ignoreras.
- [ ] `FakeAgentIT`: start, streamad prompt, permission godkänd, avböjd och timeout, cancel, process dör mitt i tur ger `error`, ingen kvarlämnad process efter stängning.
- [ ] Maxgräns: en fjärde samtidig chatt får `error` med tydligt meddelande.

**Demoscenario (manuellt)**

- [ ] Öppna `http://localhost:8080/`. Statusraden går från "agent startar" till "redo" inom 30 s.
- [ ] Skriv "Lägg till tre uppgifter: köpa mjölk, ringa Anna, boka tandläkare". Tre `create_task`-kort visas och agenten bekräftar. Uppgifterna dyker upp i uppgiftspanelen i samma stund som respektive tool-anrop är klart.
- [ ] Skriv "Markera ringa Anna som klar och visa öppna uppgifter". `complete_task` och `list_tasks` anropas, och svaret listar två uppgifter.
- [ ] Skriv "Ta bort alla uppgifter". En permission-dialog visas. Avböj, och kontrollera att inget togs bort.
- [ ] Starta en lång fråga och tryck Stopp. Turen avslutas med `stopReason` `cancelled`.
- [ ] Stäng fliken. Inom 10 s finns ingen `claude-agent-acp`-process kvar och arbetskatalogen är borta.
- [ ] Anropa `/mcp` med MCP Inspector och utvecklartoken. Samma uppgifter syns. Skapa en uppgift via Inspector: den visas direkt i uppgiftspanelen i alla öppna flikar.

## Risker och öppna frågor

Den största risken är att agenten tyst ignorerar `mcpServers`. Därför ligger spiken tidigt, i steg 3.

| Risk | Konsekvens | Åtgärd |
| --- | --- | --- |
| Agenten annonserar MCP-stöd men ansluter aldrig. Det är rapporterat för Cursors ACP-agent ([forum](https://forum.cursor.com/t/acp-agent-silently-ignores-mcpservers-in-session-new/153623)). | Kärnflödet fungerar inte | Spike i steg 3. Reserv: stdio-MCP-server via en tunn bryggprocess som proxar till `/mcp` |
| ACP Java SDK är version 0.x och API:t ändras | Kod bryts vid uppgradering | Lås versionen. Kapsla SDK:n bakom `AgentSession` |
| Agenten använder sina inbyggda tools (Bash, filer) i stället för POC:ns | Oväntade åtgärder på maskinen | Tom arbetskatalog, alla permissions till användaren, tydliga toolbeskrivningar |
| Uppstart av en agentprocess per chatt tar flera sekunder | Långsam första interaktion | Acceptabelt i POC. Senare: en process med flera sessioner |
| Kostnad för API-anrop under utveckling | Oväntad faktura | Fake-agent i alla automatiska tester. Riktig agent bara i spike och demo |

**Öppna frågor**

- Kan `claude-agent-acp` begränsas till bara MCP-tools, t.ex. via miljövariabel eller `_meta` i `session/new`? Undersöks i spiken.
- Vilken MCP-transport stöder adaptern faktiskt (HTTP, SSE, stdio)? Avgörs i spiken.
- Ska autentisering mot Claude ske med API-nyckel eller med inloggad Claude Code-prenumeration?
- Behövs chatthistorik vid omladdning av sidan (ACP `session/load`), eller räcker en ny session?
