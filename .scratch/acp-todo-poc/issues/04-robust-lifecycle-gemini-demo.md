# 04: Robust livscykel, Gemini och demo

**Status:** done (Gemini mot riktig CLI ej provat)

**Blocked by:** 03

**What to build:** En **Chatt** städar alltid efter sig, även när något går fel. En annan agent kan användas via konfiguration. En ny utvecklare kan starta POC:n och genomföra hela demon enbart med hjälp av README.

Underlag: PRD FR-9, FR-11, FR-12, NFR-3, NFR-5, NFR-6 och PRD:ns acceptanskriterier, POC-spec "Sessionslivscykel" och implementationssteg 7, ADR-0002 och ADR-0005 (känd lucka för Gemini).

- [x] När fliken stängs:
  - En pågående Tur avbryts.
  - Inom 10 s finns ingen agentprocess kvar. Processen dödas efter `shutdown-grace` om den fortfarande lever.
  - Arbetskatalogen är raderad och Chattens MCP-token ogiltig.
- [x] En Chatt utöver `max-sessions` (standard 3) får `error` med ett tydligt meddelande.
- [x] En agentprocess som dör mitt i en Tur ger `error` i Chatten.
- [x] En agent som inte annonserar `mcpCapabilities.http` ger en loggad varning och ett tydligt fel i Chatten (FR-11).
- [x] Agentprocessen ärver bara `PATH`, `HOME` och konfigurerade variabler. API-nyckeln loggas aldrig (NFR-5).
- [x] Appen städar alla Agentsessioner vid nedstängning.
- [x] Ett integrationstest med en fake-agent som subprocess (skriven med SDK:ns agentstöd) täcker start, process som dör mitt i en Tur och att ingen process blir kvar efter stängning.
- [ ] Profilen `gemini` startar Gemini CLI med `--acp` och kan skapa uppgifter. Den kända luckan i verktygsbegränsningen är dokumenterad.
- [x] README beskriver följande:
  - Förutsättningar: Java 25, Node ≥ 22, installation av `claude-agent-acp`, och inloggad Claude Code eller API-nyckel.
  - Start, felsökning och Gemini-profilen.
- [x] Hela demoscenariot i PRD:ns acceptanskriterier går igenom mot riktig `claude-agent-acp` i Chrome och Firefox.

## Kvar

- Gemini-punkten är öppen: profilen startar `gemini --acp` (`GeminiProfileIT`) och luckan är dokumenterad, men att Gemini faktiskt kan skapa uppgifter är inte provat mot en riktig Gemini CLI.
- Demoscenariot mot riktig `claude-agent-acp` är körd manuellt av Joel (inklusive MCP Inspector med utvecklartoken).
