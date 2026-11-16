# ACP Todo POC

En lokal webbapp där en utvecklare chattar med en extern kodagent (Claude Code via ACP) som hanterar en gemensam uppgiftslista genom appens egna MCP-tools. Se [PRD](docs/PRD.md), [TECH-STACK](docs/TECH-STACK.md), [ADR:er](docs/adr/) och [GLOSSARY](GLOSSARY.md).

> Status: ärende 01–04 är implementerade. Kvar är den manuella demon mot riktig agent i Chrome och Firefox (se [Demo](#demo)).

## Förutsättningar

- Java 25
- Node ≥ 22
- Agenten installerad globalt: `npm i -g @agentclientprotocol/claude-agent-acp`
- Inloggad Claude Code (prenumeration, standard) eller `ANTHROPIC_API_KEY` i miljön

Appen startar en egen agentprocess per Chatt. Agentprocessen ärver bara `PATH`, `HOME` och variablerna under `poc.agent.env`, så `HOME` måste peka på hemkatalogen där Claude Code är inloggad.

## Bygga och testa

```bash
./mvnw verify
```

## Starta appen

```bash
./mvnw spring-boot:run
```

Appen lyssnar på `127.0.0.1:8080`. MCP-servern ligger på `/mcp` (Streamable HTTP) och kräver `Authorization: Bearer <token>`.
För MCP Inspector, starta med `POC_MCP_DEV_TOKEN=<valfritt-hemligt-värde>`. Utan variabeln finns ingen utvecklartoken.

Tools: `list_tasks` (valfritt `status`-filter), `create_task`, `complete_task` och `delete_task` (destruktivt, ger alltid en Permission-förfrågan).

## Demo

Demon följer acceptanskriterierna i [PRD](docs/PRD.md#acceptanskriterier) och görs mot riktig `claude-agent-acp` i både Chrome och Firefox. Den gör riktiga modellanrop. Starta appen med `./mvnw spring-boot:run` (med `POC_MCP_DEV_TOKEN=<hemligt-värde>` för steg 8, och `ANTHROPIC_API_KEY` om du inte är inloggad i Claude Code) och öppna <http://localhost:8080>. Använd `localhost` eller `127.0.0.1`, andra värdnamn avvisas (NFR-1).

1. **Start:** Statusraden går från "Agent startar…" till "Redo" inom 30 s.
2. **Skapa:** Skriv "Lägg till tre uppgifter: köpa mjölk, ringa Anna, boka tandläkare." Tre `create_task`-kort visas utan dialog, och uppgifterna dyker upp i panelen.
3. **Slutför och lista:** Skriv "Markera ringa Anna som klar och visa öppna uppgifter." `complete_task` och `list_tasks` anropas, och svaret listar två uppgifter.
4. **Avböj borttagning:** Skriv "Ta bort alla uppgifter." En Permission-dialog visas. Avböj och kontrollera att inget har tagits bort.
5. **Verktygsbegränsning:** Be agenten köra ett skalkommando eller läsa en fil. Den ska svara att den inte kan.
6. **Stopp:** Starta en lång fråga och tryck Stopp. Turen avslutas med `cancelled`.
7. **Stäng fliken:** Inom 10 s har appen inga agentprocesser kvar, arbetskatalogen `acp-poc/<chatt-id>` är borta och Chattens MCP-token är ogiltig. Kontrollera appens egna barnprocesser (inte alla `claude-agent-acp` på maskinen, eftersom t.ex. IntelliJ kör egna): `pgrep -P "$(pgrep -f se.joelabs.acppoc.AcpPocApplication)"` ska inte skriva ut något när inga flikar är öppna.
8. **MCP Inspector:** Anropa `/mcp` med utvecklartoken och skapa en uppgift. Den visas direkt i alla öppna flikar.
9. **Gemini:** Starta om med profilen `gemini` (se nedan) och genomför steg 2.

## Konfiguration

Allt ligger i `src/main/resources/application.yml` under `poc.agent`:

| Nyckel | Standard | Betydelse |
| --- | --- | --- |
| `command`, `args` | `claude-agent-acp` | Agentkommando (FR-12) |
| `env` | `ANTHROPIC_API_KEY` | Extra variabler till agentprocessen. Loggas aldrig, bara namnen visas |
| `max-sessions` | `3` | Samtidiga Chattar. Fler får ett `error` med tydligt meddelande |
| `shutdown-grace` | `5s` | Så länge en agentprocess får avsluta sig själv när Chatten stängs. Lever den efter det dödas den |
| `permission-timeout` | `120s` | Uteblivet svar på en Permission-förfrågan räknas som avböjt |
| `workspace-root` | `${java.io.tmpdir}/acp-poc` | En tom arbetskatalog per Chatt, raderas när Chatten stängs |

## Gemini CLI som agent

Profilen `gemini` byter agent med enbart konfiguration (FR-12). Den startar `gemini --acp` (Gemini CLI ≥ 0.68; `--experimental-acp` är deprecated):

```bash
npm i -g @google/gemini-cli
./mvnw spring-boot:run -Dspring-boot.run.profiles=gemini
```

Logga in i Gemini CLI eller sätt `GEMINI_API_KEY`. Agenten ska nå appens MCP-server på samma sätt som Claude Code. Det är **inte verifierat mot en riktig Gemini CLI** i den här versionen, bara att profilen startar rätt kommando (`GeminiProfileIT`).

> **Känd lucka (ADR-0005):** Gemini ignorerar `_meta.claudeCode`, så `poc.agent.tools` och `pre-approved-tools` har ingen effekt. Gemini kan använda sina egna fil- och skaltools, och dess egna godkännanden gäller. Kör bara profilen mot en arbetskatalog och ett konto du är bekväm med.

## Felsökning

| Symptom | Orsak och åtgärd |
| --- | --- |
| "Agenten kunde inte startas: Cannot run program …" | Kommandot hittas inte. Kontrollera att `claude-agent-acp` (eller `gemini`) finns på `PATH` (`which claude-agent-acp`). Agentprocessen får bara appens `PATH` och `HOME` |
| Agenten startar men svarar med inloggningsfel | Logga in i Claude Code (`claude`) eller sätt `ANTHROPIC_API_KEY` innan appen startas |
| "Agenten stöder inte HTTP-MCP, som appens tools kräver" | Agenten annonserade inte `mcpCapabilities.http` i `initialize` (FR-11). Appen loggar en varning. Använd en agent med HTTP-MCP-stöd |
| "Max antal samtidiga chattar (3) är uppnått" | Stäng en annan flik, eller höj `poc.agent.max-sessions` |
| "Agenten svarade inte: … process exited" | Agentprocessen dog mitt i en Tur. Läs agentens stderr i appens logg (DEBUG, `[agent stderr]`) och öppna Chatten på nytt |
| WebSocket avvisas | Öppna sidan via `http://localhost:8080` eller `http://127.0.0.1:8080` (Origin-kontroll, NFR-1) |
| Kvarlämnade processer efter en krasch | Lista appens barnprocesser med `pgrep -P "$(pgrep -f se.joelabs.acppoc.AcpPocApplication)"`. Andra `claude-agent-acp`-processer, t.ex. från IntelliJ, är inte appens. Stäng appen med Ctrl-C, då städas alla Agentsessioner |
| Tester failar med "Failed to create default temp directory" | Pekar `java.io.tmpdir` mot en skrivbar katalog, t.ex. `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$TMPDIR ./mvnw verify` i en sandlåda |

## Spike mot riktig agent

Spiken startar riktig `claude-agent-acp`, skapar en session med `/mcp` som HTTP-MCP-server och en Chatt-token, begränsar agenten med `_meta.claudeCode.options` (ADR-0005) och promptar den att skapa/lista/ta bort en uppgift samt köra `ls`. **Den gör riktiga modellanrop.**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=spike
```

Spiken svarar aldrig själv på en Permission-förfrågan (NFR-7): den loggar den och svarar `cancelled`. Avsluta appen med Ctrl-C när spiken är klar.
För att prova att ett tool som inte är förgodkänt ger en Permission-förfrågan, ta bort `create_task` ur listan:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=spike \
  -Dspring-boot.run.arguments="--poc.agent.pre-approved-tools=mcp__poc-tools__list_tasks,TodoWrite"
```

### Go/no-go

**GO** (körning A och B, 2026-11-12, `claude-agent-acp` 0.106.0, Claude Code 2.1.322 som agent).

| Kontroll | Resultat |
| --- | --- |
| Agenten annonserar HTTP-MCP | ✅ `mcpCapabilities=[http=true, sse=true]` |
| Agenten anropade `create_task` och `list_tasks` på `/mcp` med rätt token | ✅ `MCP tool=create_task chatId=spike-…` och `MCP tool=list_tasks chatId=spike-…` med samma chatt-id som spiken utfärdade token för |
| Förgodkänt tool körs utan Permission-förfrågan | ✅ Ingen `PERMISSION-FÖRFRÅGAN` i loggen |
| Agenten saknar skaltool (`ls`-prompten) | ✅ Inget `Bash`-`tool_call`. Agenten svarade att den saknar skaltool. |
| Tool som inte är förgodkänt ger Permission-förfrågan | ✅ Efter att läget satts till `default` (se nedan): `PERMISSION-FÖRFRÅGAN för … create_task med alternativ [allow-once, allow-with-updates (allow_always), reject]`. Inget `MCP tool=create_task` loggades. `list_tasks` (förgodkänt) kördes utan förfrågan. |
| **Beslut** | **GO** för HTTP-MCP med bearer-token per Chatt (ADR-0003): ingen stdio-brygga behövs. Permission-grinden (FR-15, ADR-0005) fungerar, men bara om sessionens läge sätts till `default` (se nedan). |

**Observationer**

- **FR-14 (isolering från användarens konfiguration): löst med `settingSources: []` och `strictMcpConfig: true`** i `_meta.claudeCode.options` (körning 2026-11-12). Utan dem såg agenten användarens egna MCP-tools ("Claude Docs") och skills (`angular-developer` m.fl.). Med dem svarar agenten på "lista alla dina tools" att den har exakt `mcp__poc-tools__create_task` och `mcp__poc-tools__list_tasks`, nämner inte längre Claude Docs, och skills-listan innehåller inte längre användarens egna. `ls`-prompten ger fortfarande ingen skalkörning. Förbehåll: beviset är agentens egen uppgift. Ärende 02 bör kontrollera auktoritativt med `emitRawSDKMessages` (systemets init-meddelande listar tools).
- **`TodoWrite` saknas i agentens egen lista** trots att den står i `tools` och `allowedTools`. ADR-0005 och FR-7 (Planen) räknar med den. Att utreda i ärende 02 (kan vara ett uppskjutet tool eller ett annat namn i Claude Code 2.1.322).
- **Permission-läge måste sättas explicit.** Utan `permissions.defaultMode` i Claude Code-inställningarna startar `claude-agent-acp` i läget `auto`, där Claude Code själv godkänner tool-anrop det bedömer som säkra. Då ger även ett tool som inte är förgodkänt ingen Permission-förfrågan (körning B första försöket: `create_task` kördes utan fråga). `permissionMode` i `_meta.claudeCode.options` ignoreras av adaptern. Lösning: anropa `session/set_mode` med `default` direkt efter `session/new`. Det är konfiguration, inte ett svar på en förfrågan (NFR-7). Måste ingå i `AcpAgentSession` (ärende 02).
- **`allow_always` (ärende 03):** förfrågan erbjuder alternativet "Yes, and don't ask again for … commands" (`allow_always`). Väljer användaren det för `delete_task` frågar agenten inte igen, vilket bryter mot FR-15 ("`delete_task` ger alltid en Permission-förfrågan"). Besluta i ärende 03 om alternativet ska döljas i dialogen eller om det accepteras.
- Agenten varnar `CLAUDE_SDK_CAN_USE_TOOL_SHADOWED`: bara tools som står i `allowedTools` är förgodkända. Övriga går via Permission-förfrågan när läget är `default`.
- Kostnad: ca 0,07–0,08 USD per körning med de två prompterna (Opus).
- Agenten skickar `auth/status_update`, som SDK:n loggar som varning eftersom ingen handler är registrerad. Ofarligt.

Om agenten inte hade anslutit till HTTP-MCP-servern skulle arbetet ha stannat och rapporterats (ADR-0003, reserv: stdio-brygga).
