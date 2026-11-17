# White paper: Webbapplikation som MCP-server, utbyggd med en agent-prompt via ACP

Underlag: analys av `acp-todo-poc` (Spring Boot 4.1.2, Spring AI 2.0.2, ACP Java SDK 0.81.0, Java 25). Allt som sägs om POC:n är läst ur kod, ADR:er och PRD. Appen har inte körts inom ramen för analysen, och den manuella demon mot riktig agent i Chrome och Firefox är enligt README ännu inte gjord.

## 1. Sammanfattning

Mönstret har tre delar i en och samma process:

1. **Domänen** (här en uppgiftslista) är den enda sanningen och har inga kunskaper om agenter.
2. **MCP-servern** exponerar domänens operationer som *tools* över Streamable HTTP. Den är appens "API för agenter".
3. **ACP-klienten** startar en färdig kodagent (Claude Code, Gemini CLI) som subprocess och styr den över stdio. Agenten får appens egen MCP-server som verktygslåda.

Användaren skriver en prompt i en chatt. Agenten planerar, anropar appens tools och svarar. Appens UI uppdateras av domänhändelser, inte av chatten, så ändringar från agent, panel och andra MCP-klienter ser likadana ut.

Det som gör ansatsen intressant är **utbyggnadsmodellen**. Appen växer genom att man lägger till tools, inte genom att man bygger nya skärmar eller en egen agentloop. Varje nytt tool blir direkt åtkomligt via naturligt språk. Appen äger inte modellanrop, systemprompt eller kontexthantering (ADR-0001), och agenten kan bytas med en Spring-profil.

## 2. Arkitektur

```
Webbläsare ──WebSocket /ws/chat──▶ ChatWebSocketHandler
   ▲  ▲                                  │ en WS = en Chatt = en Agentsession
   │  └── REST /api/tasks (panelen)      ▼
   │                          AgentSessionRegistry ─▶ AcpAgentSessionFactory
   │                                                      │ tom workspace, token, minimal env
   │                                                      ▼
   │                              AcpAgentSession ──stdio (ACP)──▶ claude-agent-acp
   │                                                                   │ HTTP + Bearer <chatt-token>
   │                         TaskTools (@McpTool) ◀── /mcp ◀───────────┘
   │                                  │
   │                              TaskService ─▶ Task (aggregate) ─▶ ITaskEventPublisher
   └──── task_changed ◀── TaskBroadcaster (@EventListener) ◀─────────┘
```

Backend har två roller mot samma agent: **ACP-klient** (styr agenten) och **MCP-server** (agenten anropar den). En tur går så här:

1. Frontend skickar `prompt` över WebSocket.
2. `AcpAgentSession` skickar `session/prompt` till agentprocessen.
3. Agenten anropar modellen, väljer ett tool och rapporterar `session/update` (tool_call, plan, textchunks).
4. Vid behov skickar agenten `session/request_permission`, som backend vidarebefordrar till användaren.
5. Agenten anropar `tools/call` på `/mcp` med chattens token. `TaskTools` delegerar till `TaskService`.
6. Domänen skapar en händelse, `TaskBroadcaster` skickar `task_changed` till alla öppna flikar.
7. `session/prompt` returnerar med `stopReason`, och frontend får `turn_end`.

### Lagerindelning

| Del | Paket | Roll |
| --- | --- | --- |
| Domän | `task/domain` | `Task` (Aggregate Root), sealed `TaskChanged` med `TaskCreated/Renamed/Completed/Deleted`, portar `ITaskRepository` och `ITaskEventPublisher` |
| Application | `task/application` | Tunn `TaskService`: hämta, delegera, publicera händelser, returnera `TaskDto` |
| Driving adapters | `task/adapter/mcp`, `task/adapter/rest` | `TaskTools` (MCP) och `TaskController` (REST) anropar samma `TaskService` |
| Driven adapters | `task/adapter/memory`, `task/adapter/spring` | In-memory-repository och Spring-event-publicering |
| Agentintegration | `agent`, `events` | `IAgentSession` (port) med ACP-adapter bakom, `AcpEventMapper` översätter ACP till UI-händelser |
| Säkerhet för MCP | `mcp` | `McpAuthFilter`, `McpTokenService`, `ToolCallLog` |
| Webb | `web` | WebSocket-handler och `TaskBroadcaster` |

Beroenden pekar inåt. Domänen känner varken till MCP, ACP eller Spring-events.

## 3. De fem byggstenarna

### 3.1 Domän och händelser

`Task` är en Aggregate Root som validerar sina invarianter (titel 1–200 tecken efter trimning, slutförd kan inte öppnas igen) och samlar domänhändelser som `TaskService` publicerar efter varje lyckad ändring. Idempotenta operationer (slutföra en slutförd uppgift, byta till samma titel) ger ingen händelse. Det är händelserna som driver liveuppdateringen, så ett felaktigt "ändrat"-meddelande undviks.

### 3.2 MCP-servern

- Spring AI:s `spring-ai-starter-mcp-server-webmvc` med `protocol: STREAMABLE`, `type: SYNC`. Endpoint `/mcp`.
- Varje tool är en metod med `@McpTool` och `@McpToolParam` på en Spring-böna (`TaskTools`). Metoden gör ingen affärslogik, den översätter och delegerar.
- **Beskrivningarna är prompt-material.** De skrivs för modellen och avgör om agenten väljer rätt tool. De anger även beteende som modellen annars gissar fel på ("att slutföra en redan slutförd uppgift är ofarligt", "ger fel om id saknas").
- Destruktiva tools märks med `destructiveHint = true` (`delete_task`).
- Alla anrop loggas med toolnamn, chatt-id och duration via `ToolCallLog`.

### 3.3 ACP-klienten

- Backend startar `claude-agent-acp` (npm) som subprocess med `AgentParameters`, `inheritEnvironment(false)` och en explicit miljö.
- `AcpAgentSession.start()` gör `initialize`, kontrollerar att agenten annonserar `mcpCapabilities.http`, anropar `session/new` med appens MCP-server som `McpServerHttp` (med `Authorization: Bearer <token>`) och sätter sessionsläget `default`.
- Turer körs på virtuella trådar. `session/update` strömmar via `sessionUpdateHandler` och mappas av `AcpEventMapper` (Adapter) till ett litet, slutet protokoll av `UiEvent`-records. Frontend känner aldrig till ACP.
- **En agentprocess per Chatt** (ADR-0002): full isolering och enkel städning, till priset av några sekunder starttid och en gräns (`max-sessions`, standard 3).
- All ACP-kunskap kapslas bakom `IAgentSession`. SDK:n är 0.x, så det är där API-ändringar ska landa.

### 3.4 Per-Chatt-token

`McpTokenService` utfärdar 32 slumpbyte (Base64URL) per Chatt. Token ger tre saker: autentisering mot `/mcp`, koppling mellan tool-anrop och Chatt i loggen, och att åtkomsten upphör när Chatten stängs. En utvecklartoken (`POC_MCP_DEV_TOKEN`) finns bara om variabeln är satt, så att MCP Inspector kan användas.

### 3.5 Liveuppdatering

`TaskBroadcaster` lyssnar på `TaskChanged` och skickar `task_changed` till alla öppna flikar. Snapshot och händelser serialiseras i samma lås så att en gammal snapshot aldrig skriver över en nyare händelse. Panelens egna ändringar går via REST (ADR-0006) till samma `TaskService`, inte via chatten.

## 4. Utbyggnad med agent-prompt

"Bygga ut med en prompt" kan betyda två olika saker, och POC:n illustrerar den första direkt och den andra bara delvis.

### 4.1 Prompt som körtidsgränssnitt mot nya förmågor

Varje ny förmåga läggs till på tre ställen och får då ett nytt naturligt-språk-gränssnitt utan UI-arbete:

1. En **domänoperation** (och dess händelse) på aggregatet.
2. En **`TaskService`-metod** som delegerar och publicerar.
3. Ett **`@McpTool`** i `TaskTools` med en bra beskrivning, plus en rad i `poc.agent.tools` och, om det är ofarligt, i `poc.agent.pre-approved-tools`.

Commit `3730bfa` är ett konkret exempel. `rename_task` tillkom som domänoperation `rename` + `TaskRenamed`, en `TaskService.rename`, ett MCP-tool och ett REST-endpoint, och agenten kunde från det ögonblicket byta titel på uppgifter i fritext. Panelen och `TaskBroadcaster` behövde inte ändras mer än att mappa den nya händelsen.

Agentens förmåga att kombinera tools ger sedan sammansatt beteende gratis. Prompten "Markera ringa Anna som klar och visa öppna uppgifter" blir `list_tasks` → `complete_task` → `list_tasks` utan en rad orkestreringskod i appen.

### 4.2 Prompt som utvecklingsverktyg

Själva projektet är byggt med samma agentidé på utvecklarsidan: krav i `docs/PRD.md` med ID:n (FR/DR/NFR), arkitekturbeslut som ADR:er, ett `GLOSSARY.md` som delat språk, ärenden som markdownfiler under `.scratch/`, och `AGENTS.md` som pekar ut var agenten hittar allt. Testnamn, commitformat och Checkstyle-hook gör agentens arbete verifierbart. Det är ett arbetssätt som går att återskapa oberoende av resten.

### 4.3 Gräns: agenten ändrar inte koden

Agenten i POC:n kan **inte** skriva eller ändra appens kod. Den får bara appens tools och `TodoWrite` (ADR-0005). Att låta en prompt generera nya tools i den körande appen (self-extending) ingår alltså inte och är en separat designfråga med egen riskbild. Utbyggnaden är utvecklarstyrd, och prompten används för att *använda* det som byggts.

## 5. Säkerhetsmodell

Största risken är vad en kodagent gör på utvecklarens maskin, inte MCP-servern. POC:n lägger fyra lager:

| Lager | Åtgärd | Var |
| --- | --- | --- |
| Isolering | Tom arbetskatalog per Chatt, raderas vid stängning | `AcpAgentSessionFactory` |
| Miljö | Processen ärver bara `PATH`, `HOME` och konfigurerade variabler | `environment()` |
| Verktygsbegränsning | `_meta.claudeCode.options`: `tools`, `allowedTools`, `settingSources: []`, `strictMcpConfig: true` | `AcpAgentSession.sessionMeta()` |
| Mänskligt godkännande | `delete_task` ger alltid Permission-förfrågan. Backend svarar aldrig själv (NFR-7). Uteblivet svar efter 120 s räknas som avböjt | `awaitUserDecision` |

Övriga skydd:

- `/mcp` kräver bearer-token. Filtret registreras via `FilterRegistrationBean` så att servletcontainern matchar normaliserad sökväg. Det motverkar kringgång med `//mcp` eller `/mcp;x`.
- Ändrande REST-anrop kräver `Origin` ur en tillåtelselista och `Content-Type: application/json` (`RestOriginFilter`, CSRF-skydd utan inloggning). WebSocket har samma origin-lista.
- Appen lyssnar bara på `127.0.0.1`.
- Prompt-injektion: agenten läser bara data från appens egna tools, men uppgiftstitlar är fri text som modellen ser. Permission-dialogen visar därför anropets råa indata (`rawInput`, trunkerad) och inte bara agentens egen beskrivning, som betraktas som opålitlig.

**Känd lucka:** Gemini-profilen ignorerar `_meta.claudeCode`, så där gäller bara de tre första lagren och agentens egna verktyg. Den finns för att visa utbytbarhet, inte som säker konfiguration.

## 6. Teststrategi

- **Ingen riktig modell i automatiska tester** (NFR-8). `FakeAgent` är byggd på SDK:ns riktiga agentsida (inte en mock), skriptad via promptens text (`slow`, `permission`, `die`), och körs både in-memory och som subprocess (`FakeAgentProcessIT`).
- **MCP-sömmen** testas över HTTP med MCP Java SDK-klient (`McpEndpointIT`: token, verktygslista, loggning, 401).
- **WebSocket-flödet** testas end-to-end mot fake-agent (`ChatWebSocketIT`).
- **Enhetstester** för domän, `TaskService` och `AcpEventMapper`.
- **Spike-profil** (`spike`) mot riktig agent för att verifiera de antaganden som bara lästs ur källkod: att agenten ansluter till HTTP-MCP, att `_meta`-begränsningen verkar och att Permission uppstår för icke-förgodkända tools. Resultatet är dokumenterat i README.
- Checkstyle (komplexitet ≤ 10) körs i pre-commit-hook och CI (`./mvnw -B verify`).

## 7. Steg-för-steg-recept

1. **Skelett och spike först.** Starta Spring Boot med MCP-server, ett enda tool och en MCP-klient i ett integrationstest. Bevisa sedan med en engångs-runner mot riktig agent att den faktiskt ansluter. Gör det innan något UI byggs. Stanna och rapportera om det inte fungerar, bygg inte runt det (reserv: stdio-brygga, ADR-0003).
2. **Domän och portar.** Aggregate Root, domänhändelser, tunn application service. Håll domänen fri från MCP/ACP.
3. **MCP-adaptern.** `@McpTool`-metoder som bara översätter. Lägg tid på beskrivningarna. Märk destruktiva tools.
4. **Token och filter.** Per-Chatt-token, bearer-filter registrerat via container-matchade URL-mönster, tool-logg med chatt-id.
5. **`IAgentSession` + ACP-adapter.** Process per Chatt, `initialize`, kontroll av `mcpCapabilities.http`, `session/new` med MCP-server och token, sessionsläge `default`, tidsgränser, städning (`destroyForcibly` efter grace, token återkallas, katalog raderas).
6. **Händelsemappning.** Ett slutet `UiEvent`-protokoll. ACP stannar i `AcpEventMapper`. Ignorera okända varianter (logga på DEBUG).
7. **WebSocket och liveuppdatering.** En WS = en Chatt. `TaskBroadcaster` som Observer. Synkronisera skrivningar (`ConcurrentWebSocketSessionDecorator`).
8. **Permission-flöde.** Backend svarar aldrig själv. Tidsgräns, avbrott och stängning ger `cancelled`.
9. **Fake-agent och tester** parallellt med varje steg, inte efteråt.
10. **Konfiguration.** Agentkommando, args, env, `tools`, `pre-approved-tools`, `max-sessions`, tidsgränser i `application.yml`. Agentbyte via Spring-profil.
11. **Utbyggnadsloop.** För varje ny förmåga: domänoperation → service → tool → konfigurationsrad → test → prompt som verifierar.

## 8. Avvägningar och risker

| Område | Observation |
| --- | --- |
| Beroenden | Node ≥ 22 och en installerad agent-CLI krävs hos användaren. Appen styr inte modellanrop, systemprompt eller kontext. |
| Starttid | Några sekunder per Chatt eftersom varje Chatt har egen process. Mål: redo inom 30 s. |
| Skalning | En process per Chatt är lämpligt lokalt, inte för många användare. Byte till delad process påverkar bara adaptern bakom `IAgentSession`, men isolering och städning måste byggas om. |
| SDK-mognad | ACP Java SDK är 0.x (version låst, kapslad bakom porten). `claude-agent-acp`-specifika `_meta`-nycklar är en implicit kontrakt mot en adapter. |
| Agentberoende säkerhet | Verktygsbegränsningen fungerar bara för Claude Code. Andra agenter kräver egna lösningar. |
| Persistens och användare | POC:n har inget av dem: minnesbaserad lista, ingen inloggning, `localhost` bara. Produktion kräver autentisering av användaren (inte bara av Chatten), persistens, auktorisering per tool och revisionsspår. |
| Samtidighet | Senast skrivna vinner, ingen versionskontroll. Det räcker utan persistens, men inte i en delad produktionsmiljö. |
| Kostnad | Riktiga modellanrop vid demo och spike. Fake-agent håller CI gratis. |

## 9. Slutsats

Mönstret är genomförbart och billigt att utöka: domänen exponeras en gång som MCP-tools, och en färdig agent ger planering, tool-val, strömning och godkännanden utan egen agentloop. De avgörande designvalen är (1) att domänhändelserna, inte chatten, driver UI, (2) att ACP-kunskap hålls bakom en port och en mapper, (3) att agentens räckvidd begränsas i konfiguration och inte i dold kod, och (4) att säkerheten bygger på isolering och mänskligt godkännande snarare än på förtroende för modellen. Det som återstår för en produkt är användarautentisering, persistens, och ett ställningstagande till om agenten någonsin ska få förändra själva appen.
