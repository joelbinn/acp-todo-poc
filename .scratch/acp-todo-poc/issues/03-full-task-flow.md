# 03: Hela uppgiftsflödet: slutföra, ta bort, godkänna och liveuppdatera

**Status:** implemented (väntar på manuell kontroll med MCP Inspector)

**Blocked by:** 02

**What to build:** Användaren kan låta agenten slutföra och ta bort **Uppgifter**. Borttagning kräver alltid att användaren svarar på en **Permission-förfrågan**. En uppgiftspanel bredvid Chatten visar hela **Uppgiftslistan** och uppdateras direkt i alla öppna flikar, oavsett vilken MCP-klient som gjorde ändringen.

Underlag: PRD FR-5, FR-13, FR-15 och DR-3–DR-6, NFR-7, POC-spec "Permissions" och "Liveuppdatering av uppgifter", ADR-0005.

- [x] `complete_task` slutför en öppen Uppgift och ger `TaskCompleted`. Att slutföra en redan slutförd Uppgift lyckas utan händelse (DR-3). En slutförd Uppgift kan inte öppnas igen (DR-4).
- [x] `delete_task` är annoterat som destruktivt och ger `TaskDeleted`. Okänt id ger ett läsbart toolfel för både `complete_task` och `delete_task` (DR-5).
- [x] `delete_task` ger alltid en Permission-förfrågan, medan `list_tasks`, `create_task`, `complete_task` och `TodoWrite` är förgodkända.
- [x] Permission-förfrågan visas som en dialog med en knapp per alternativ från agenten. Användarens val når agenten.
- [x] Uteblivet svar inom 120 s räknas som avböjt. En stängd flik ger `cancelled`. Backend svarar aldrig själv.
- [x] Agentsessionen är testad mot in-memory-transporten för permission godkänd, avböjd och timeout.
- [x] När en flik öppnas får den `tasks_snapshot`. Varje Uppgiftshändelse når alla öppna flikar som `task_changed`, och det är testat med flera WebSocket-sessioner.
- [x] Panelen visar öppna uppgifter överst. Nya eller ändrade rader markeras i ca 2 s, och borttagna rader tonas ut. Panelen uppdateras från händelser, inte från agentens text.
- [x] Samtidiga skrivningar till samma WebSocket-session (agentström och uppgiftshändelser) är synkroniserade.
- [ ] Manuellt: en uppgift som skapas via MCP Inspector med utvecklartoken syns direkt i alla öppna flikar.
