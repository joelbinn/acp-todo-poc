# Agentens säkerhetsgräns och förgodkända tools

Status: accepted · 2026-11-12

Agenten är en kodagent som har egna tools för filer, skal och webb. Därför är den största risken vad agenten gör på utvecklarens maskin, inte MCP-servern. Vi begränsar den i två lager:

1. **Grundskydd för alla agenter.** Följande gäller oavsett agent:
   - Agentens `cwd` är en tom arbetskatalog per Chatt.
   - Processen ärver bara `PATH`, `HOME` och konfigurerade variabler.
   - Backend svarar aldrig själv på en Permission-förfrågan.
2. **Verktygsbegränsning i Claude Code.** I `session/new` sätter `_meta.claudeCode.options` vilka tools agenten får använda:
   - Tillåtna är bara `mcp__poc-tools__*` och `TodoWrite`. `TodoWrite` krävs för att Planen ska visas.
   - `list_tasks`, `create_task` och `complete_task` är förgodkända.
   - `delete_task` ger alltid en Permission-förfrågan.

Vi skiljer på att *konfigurera* förgodkända tools och att *svara på* en förfrågan. Konfigurationen kan läsas i `application.yml`. Ett automatiskt svar i koden skulle däremot vara osynligt och svårt att granska.

## Considered Options

- **Alla tools frågar:** demon drunknar i dialoger, och skillnaden mellan läsning/skrivning och destruktivt syns inte.
- **`.claude/settings.json` i arbetskatalogen:** fungerar troligen, men det är en fil vi måste skriva och städa, och den gäller bara Claude Code. `_meta` är explicit per session.

## Consequences

- **Känd lucka:** profilen `gemini` förstår inte `_meta.claudeCode`. Där gäller bara grundskyddet, och agenten har tillgång till sina inbyggda tools. Profilen finns bara för att visa att agenten kan bytas ut.
- Att `_meta.claudeCode.options` fungerar har bara lästs ut ur adapterns källkod. Spiken (steg 3) verifierar det.
