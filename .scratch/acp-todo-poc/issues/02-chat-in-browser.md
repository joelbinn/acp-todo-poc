# 02: Chatt i webbläsaren

**Status:** implemented (väntar på manuell demo mot riktig agent)

**Blocked by:** 01 (spiken måste ha gett go)

**What to build:** En användare öppnar `http://localhost:8080/`, får en ny **Chatt** med en egen **Agentsession** och chattar med agenten i realtid. Under en **Tur** syns svarstext, tool-anrop, agentens tankar och agentens **Plan** medan de händer. En pågående Tur kan avbrytas.

Detta är första vägen genom hela kedjan: webbläsare → WebSocket → porten `IAgentSession` → ACP-adapter → agent. ACP-detaljer stannar i event-mappningen, och frontend känner bara till protokollet i POC-specens WebSocket-tabeller.

Underlag: PRD FR-1–FR-4 och FR-6–FR-8, POC-spec "ACP-integration" och "Frontend och WebSocket-protokoll", ADR-0002 och ADR-0004.

- [x] Statusraden går från "agent startar" till "redo" inom 30 s (NFR-2). Frontend får `session_ready`.
- [x] Ett meddelande skickas som `session/prompt`, och svaret streamas som `agent_text`. Turen avslutas med `turn_end`, stoppskälet visas diskret och inmatningen låses upp.
- [x] Tool-anrop visas som kort med namn, status (pågår, klar, fel) och ett kollapsbart resultat, trunkerat till 2 000 tecken.
- [x] `agent_thought` visas nedtonat. Planen visas som en lista som uppdateras på plats.
- [x] Stoppknappen skickar `session/cancel`, och Turen slutar med `cancelled`. Skicka-knappen är låst medan en Tur pågår.
- [x] Okända `session/update`-varianter loggas på DEBUG och ignoreras.
- [x] WebSocket accepterar bara origin `localhost:8080` och `127.0.0.1:8080` (NFR-1).
- [x] Event-mappningen har ett testfall per händelsetyp i protokolltabellen.
- [x] Agentsessionen testas mot SDK:ns in-memory-transport: streaming, cancel och att `_meta.claudeCode.options` skickas i `session/new`. Inga mockar av ACP-SDK:n.
- [x] Ett WebSocket-test mot fake-agenten får `session_ready` → `agent_text` → `turn_end`.

## Comments

- Implementerat med TDD. Manuellt verifierat: riktig `claude-agent-acp` ger `session_ready` och statusraden "Redo" i webbläsaren. En riktig prompt mot modellen har inte körts.
- Kodgranskning: åtgärdat ordning tanke/text i `app.js`, sticky "Fel"-status, `prompt` före `session_ready`, startbudget 2×15 s (NFR-2). Kvar: markdown i agentbubblor (spec nämner det, ticketen inte).
