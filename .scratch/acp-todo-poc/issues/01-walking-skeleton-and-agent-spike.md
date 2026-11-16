# 01: Walking skeleton och spike mot riktig agent

**Status:** done

**Blocked by:** None (can start immediately)

**What to build:** Bevisa kärnan i POC:n innan något UI byggs: att en riktig `claude-agent-acp` kan skapa och lista **Uppgifter** genom appens egen MCP-server.

Underlag: [PRD](../../../docs/PRD.md), [TECH-STACK](../../../docs/TECH-STACK.md), [POC-spec](../../../docs/POC-SPEC.md) (implementationssteg 1–3), [GLOSSARY](../../../GLOSSARY.md), ADR-0003, ADR-0004 och ADR-0005.

Ärendet har tre delar:

1. **Stacken startar.** Appen startar på Java 25, Spring Boot 4.1.2 och Spring AI 2.0.2, och MCP-servern kommer upp på `/mcp` (Streamable HTTP).
2. **Uppgifter via MCP.** En MCP-klient med giltig token kan skapa och lista Uppgifter. Uppgiftsdomänen byggs hexagonalt enligt ADR-0004:
   - Uppgift är Aggregate Root.
   - `TaskService` publicerar `TaskCreated` via en port.
   - Repository och händelsepublicering är adaptrar.
3. **Spike mot riktig agent.** En spike-profil startar riktig `claude-agent-acp`, skapar en ACP-session med `/mcp` som HTTP-MCP-server och skickar en Chattens token. Agenten begränsas med `_meta.claudeCode.options` (ADR-0005). Spiken promptar agenten att skapa och lista uppgifter och att köra ett skalkommando.

Om spiken visar att agenten inte ansluter till HTTP-MCP-servern ska arbetet stanna och rapporteras, inte byggas runt.

- [x] Appen startar och `GET /` svarar 200. MCP-autokonfigurationen startar. Kräver Spring AI en annan Boot-version sänks Boot, och det dokumenteras i TECH-STACK.
- [x] `list_tasks` (med valfritt statusfilter) och `create_task` syns och fungerar via en MCP Java SDK-klient i ett integrationstest.
- [x] DR-1, DR-2 och DR-6 är testade:
  - Id:n är löpnummer.
  - Titeln trimmas och måste vara 1–200 tecken.
  - Dubblettitlar är tillåtna.
  - Ogiltig titel ger ett läsbart toolfel.
  - Varje lyckad ändring ger exakt en `TaskCreated`.
- [x] `/mcp` ger 401 utan token eller med fel token. Utvecklartoken accepteras bara när `POC_MCP_DEV_TOKEN` är satt (NFR-4).
- [x] Varje tool-anrop loggas med toolnamn, chatt-id och duration (NFR-9).
- [x] Spike, att verifiera:
  - [x] Loggen visar att agenten anropat `create_task` och `list_tasks` på `/mcp` med rätt token.
  - [x] Agenten saknar skaltool.
  - [x] Ett förgodkänt tool körs utan Permission-förfrågan.
  - [x] Ett tool som inte är förgodkänt ger en Permission-förfrågan. Prova genom att tillfälligt ta bort `create_task` ur listan över förgodkända tools.
- [x] Go/no-go-beskedet och vad som observerades i spiken är dokumenterat i README.
- [x] `./mvnw verify` är grönt. Testnamn följer `should_X_when_Y`.
