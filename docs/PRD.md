# PRD: ACP Todo POC

2026-11-12 · Joel Binnquist · Status: godkänd

Begrepp i fetstil definieras i [`GLOSSARY.md`](../GLOSSARY.md). Hur det byggs beskrivs i [POC-specifikationen](./POC-SPEC.md), med vad i [`TECH-STACK.md`](./TECH-STACK.md) och varför arkitekturen ser ut som den gör i [`adr/`](./adr/).

## Problem

Vi vill veta om en vanlig Spring Boot-app kan använda en färdig kodagent (Claude Code, Gemini CLI) som "hjärna" i stället för att bygga en egen agentloop. Agenten ska då arbeta mot appens egen domän genom tools som appen själv exponerar. Det finns inget färdigt exempel på hela kedjan i Java: webbläsare → backend → ACP-agent → backendens egen MCP-server.

## Mål

1. Visa att hela kedjan fungerar från början till slut mot riktig `claude-agent-acp`.
2. Visa att agenten kan bytas utan kodändring (Gemini CLI via konfiguration).
3. Visa att användaren har kontroll: destruktiva åtgärder kräver godkännande, och agenten kan inte röra utvecklarens maskin utanför POC:ns tools.
4. Ge ett underlag för att avgöra om ansatsen bär vidare mot en riktig produkt.

## Icke-mål

- Inloggning och flera användare. En utvecklare kör POC:n lokalt.
- Persistens. **Uppgiftslistan** och chatthistoriken lever i minnet.
- Återupptagning av en **Chatt** efter omladdning (`session/load`). Omladdning ger en ny Chatt.
- Egen agentloop med Spring AI eller LangChain4j.
- Produktionsdrift, containerisering och skalning.

## Användare

En utvecklare som kör appen på sin egen maskin och har antingen en inloggad Claude Code-prenumeration eller en `ANTHROPIC_API_KEY`.

## Användarflöden

1. **Starta en Chatt.** Användaren öppnar `http://localhost:8080/`. Statusraden visar "agent startar" och sedan "redo". Uppgiftspanelen visar nuvarande Uppgiftslista.
2. **Hantera uppgifter i fritext.** Användaren skriver "Lägg till tre uppgifter …". Svaret streamas, varje tool-anrop visas som ett kort och uppgifterna dyker upp i panelen när respektive anrop är klart.
3. **Godkänna en destruktiv åtgärd.** Användaren ber agenten ta bort uppgifter. En dialog visar agentens alternativ, och användaren godkänner eller avböjer.
4. **Avbryta.** Användaren trycker Stopp under en pågående **Tur**. Turen avslutas med stoppskälet `cancelled`.
5. **Se ändringar från andra.** En annan flik eller MCP Inspector ändrar Uppgiftslistan. Panelen uppdateras direkt i alla öppna flikar.
6. **Redigera direkt i panelen.** Användaren skapar, byter titel på, slutför och tar bort Uppgifter med vanliga kontroller (inmatningsfält, checkbox, knappar). Ändringen syns i alla öppna flikar och för agenten.

## Funktionella krav

| ID | Krav |
| --- | --- |
| FR-1 | Användaren öppnar `http://localhost:8080/` och får en chattvy. Varje ny anslutning är en ny Chatt med en egen **Agentsession**. |
| FR-2 | Backend startar agentprocessen och anropar `initialize` och `session/new`. Backendens MCP-server anges som HTTP-server i `mcpServers`. |
| FR-3 | Användarens meddelande skickas som `session/prompt`. Agentens svar streamas till frontend i realtid. |
| FR-4 | Tool-anrop visas i Chatten med namn, status (pågår, klar, fel) och ett kort resultat. |
| FR-5 | En **Permission-förfrågan** visas som en dialog med agentens alternativ, och användarens val skickas tillbaka. Uteblivet svar inom 120 s räknas som avböjt. |
| FR-6 | Användaren kan avbryta en pågående Tur med en stoppknapp (`session/cancel`). |
| FR-7 | Agentens **Plan** visas som en lista som uppdateras på plats. |
| FR-8 | När en Tur är klar visas stoppskälet diskret och inmatningen låses upp. |
| FR-9 | När WebSocket-anslutningen stängs avslutas Chatten. Agentprocessen är borta och arbetskatalogen raderad inom 10 s. |
| FR-10 | MCP-servern exponerar POC:ns **Tools** på `/mcp`. Valfri MCP-klient med giltig token kan anropa dem. |
| FR-11 | Om agenten inte annonserar `mcpCapabilities.http` loggas en varning och användaren får ett tydligt felmeddelande i Chatten. |
| FR-12 | Agentkommando, argument, miljövariabler och **förgodkända tools** konfigureras i `application.yml`. Agenten byts genom en Spring-profil. |
| FR-13 | Uppgiftspanelen visar alla Uppgifter. Ändringar, från vilken MCP-klient som helst, når alla öppna flikar direkt utan omladdning. |
| FR-14 | Med Claude Code som agent kan agenten bara använda POC:ns Tools och sitt plan-tool (`TodoWrite`), inga fil-, skal- eller webbtools. |
| FR-15 | `list_tasks`, `create_task`, `rename_task` och `complete_task` är förgodkända. `delete_task` ger alltid en Permission-förfrågan. |
| FR-16 | Uppgiftspanelen är redigerbar. Användaren kan skapa en Uppgift (inmatningsfält, Enter), byta titel (penn-knapp eller dubbelklick; Enter och förlorad fokus sparar, Esc avbryter, oförändrad eller tom titel avbryter tyst), slutföra (checkbox, låst för slutförda) och ta bort (knapp, direkt utan dialog). Panelen uppdateras bara från Uppgiftshändelser (FR-13), aldrig optimistiskt. |
| FR-17 | Panelen anropar REST: `POST /api/tasks`, `PATCH /api/tasks/{id}` (titel), `POST /api/tasks/{id}/complete` och `DELETE /api/tasks/{id}`. Alla går via `TaskService`. Ogiltig titel ger 400 och okänt id 404, båda med ett läsbart felmeddelande som visas på raden. |
| FR-18 | MCP-servern har ett femte tool, `rename_task`, som byter titel på en Uppgift. |
| FR-19 | Samtidiga ändringar: senast skrivna vinner. En Uppgiftshändelse skriver aldrig över text som användaren håller på att skriva. Tas Uppgiften bort under redigering stängs redigeringsläget. |

### Regler för Uppgiftslistan

| ID | Regel |
| --- | --- |
| DR-1 | En Uppgift har ett löpnummer som id (`1`, `2`, …), en titel, en **Status** (`OPEN`, `DONE`) och en skapandetid. |
| DR-2 | Titeln trimmas och måste sedan vara 1–200 tecken. Dubblettitlar är tillåtna. |
| DR-3 | Att slutföra en redan slutförd Uppgift lyckas och ändrar ingenting, och ingen **Uppgiftshändelse** uppstår. |
| DR-4 | En slutförd Uppgift kan inte öppnas igen. |
| DR-5 | Att byta titel på, slutföra eller ta bort ett okänt id ger ett läsbart toolfel, aldrig en stacktrace. |
| DR-6 | Varje lyckad ändring ger exakt en Uppgiftshändelse: `TaskCreated`, `TaskRenamed`, `TaskCompleted` eller `TaskDeleted`. |
| DR-7 | Titeln kan bytas oavsett Status. Den nya titeln valideras som i DR-2. En titel som är oförändrad efter trimning lyckas utan ändring och utan Uppgiftshändelse. |

## Icke-funktionella krav

| ID | Krav |
| --- | --- |
| NFR-1 | Appen lyssnar bara på `127.0.0.1`. WebSocket accepterar bara origin `localhost:8080` och `127.0.0.1:8080`. |
| NFR-2 | En Chatt är redo inom 30 s från att sidan öppnats. |
| NFR-3 | Högst 3 samtidiga Chattar (konfigurerbart). En Chatt utöver gränsen får ett tydligt fel. |
| NFR-4 | `/mcp` kräver en bearer-token. Varje Chatt har en egen token som slutar gälla när Chatten stängs. En utvecklartoken finns bara om `POC_MCP_DEV_TOKEN` är satt. |
| NFR-5 | API-nycklar loggas aldrig. Agentprocessen får bara `PATH`, `HOME` och de variabler som är konfigurerade. |
| NFR-6 | Agentens arbetskatalog är en tom katalog per Chatt, aldrig källkoden eller hemkatalogen. |
| NFR-7 | Backend svarar aldrig själv på en Permission-förfrågan. |
| NFR-8 | Automatiska tester anropar aldrig en riktig modell. |
| NFR-9 | Varje tool-anrop loggas med toolnamn, chatt-id och duration. |
| NFR-10 | Ändrande REST-anrop (FR-17) kräver `Origin` ur `poc.web.allowed-origins` (annars 403) och `Content-Type: application/json`, så att en annan webbplats i användarens webbläsare inte kan ändra listan (CSRF). |

## Acceptanskriterier

POC:n är klar när alla automatiska tester är gröna och följande demo fungerar mot riktig `claude-agent-acp` i Chrome och Firefox:

- [ ] Öppna appen. Statusraden går från "agent startar" till "redo" inom 30 s.
- [ ] "Lägg till tre uppgifter: köpa mjölk, ringa Anna, boka tandläkare." Tre `create_task`-kort visas utan dialog, och uppgifterna dyker upp i panelen när respektive anrop är klart.
- [ ] "Markera ringa Anna som klar och visa öppna uppgifter." `complete_task` och `list_tasks` anropas, och svaret listar två uppgifter.
- [ ] "Ta bort alla uppgifter." En Permission-dialog visas. Avböj, och kontrollera att inget har tagits bort.
- [ ] Be agenten köra ett skalkommando eller läsa en fil. Den kan inte, eftersom den saknar sådana tools.
- [ ] Starta en lång fråga och tryck Stopp. Turen avslutas med `cancelled`.
- [ ] Stäng fliken. Inom 10 s finns ingen agentprocess kvar och arbetskatalogen är borta.
- [ ] Anropa `/mcp` med MCP Inspector och utvecklartoken, och skapa en uppgift. Den visas direkt i alla öppna flikar.
- [ ] Redigera i panelen: skapa en Uppgift, byt titel (Enter sparar, Esc avbryter), slutför och ta bort. Ändringarna syns i en andra flik, och agentens `list_tasks` ser dem.
- [ ] Starta med profilen `gemini` och genomför steg 2.

## Risker och öppna frågor

| Risk / fråga | Hur den avgörs |
| --- | --- |
| Agenten annonserar HTTP-MCP men ansluter aldrig (har rapporterats för andra ACP-agenter). | Spike i implementationssteg 3. Reserv: en stdio-brygga ([ADR-0003](./adr/0003-mcp-over-http-with-per-chat-token.md)). |
| Begränsningen av tools och förgodkännandet via `_meta.claudeCode.options` fungerar inte som källkoden antyder. | Verifieras i spiken. Annars gäller grundskyddet i [ADR-0005](./adr/0005-agent-security-boundary.md). |
| Gemini-profilen kan inte begränsa agentens inbyggda tools. | Accepterat som känd lucka ([ADR-0005](./adr/0005-agent-security-boundary.md)). |
| ACP Java SDK är 0.x och API:t ändras. | Versionen låses, och SDK:n kapslas bakom porten `IAgentSession`. |
| Kostnad för API-anrop. | Fake-agent i alla automatiska tester (NFR-8). |
