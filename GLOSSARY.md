# ACP Todo POC

En lokal webbapp där en utvecklare chattar med en extern kodagent som hanterar en gemensam uppgiftslista genom appens egna tools.

## Uppgiftslistan

**Uppgift** (`Task`):
En post i den gemensamma uppgiftslistan med ett löpnummer som id, en titel och en status.
_Avoid_: TODO, todo-post, ärende

**Uppgiftslista**:
Samlingen av alla Uppgifter. Den är gemensam för alla Chattar och MCP-klienter.
_Avoid_: TODO-lista, backlog

**Status** (`OPEN`, `DONE`):
Om en Uppgift är öppen eller slutförd. En slutförd Uppgift kan inte öppnas igen.

**Uppgiftshändelse** (`TaskCreated`, `TaskCompleted`, `TaskDeleted`):
Ett faktum om att en Uppgift har skapats, slutförts eller tagits bort.
_Avoid_: TaskChangedEvent, uppdatering

## Chatt och agent

**Chatt**:
En konversation mellan användaren och en Agent. Den lever lika länge som webbläsarfliken är ansluten och har en egen Agentsession, MCP-token och arbetskatalog. Omladdning av sidan ger en ny Chatt.
_Avoid_: session, konversation, tråd

**Agent**:
Den externa kodagenten (t.ex. Claude Code via `claude-agent-acp`) som appen styr och som anropar appens tools.
_Avoid_: bot, assistent, LLM

**Agentsession** (`AgentSession`):
En Chatts koppling till sin Agent: en agentprocess med en ACP-session.
_Avoid_: ACP-session (som fristående begrepp), process

**Tur**:
Ett användarmeddelande och allt Agenten gör tills den svarar med ett stoppskäl.
_Avoid_: request, runda

**Plan**:
Agentens egna arbetssteg för en Tur (planposter). Planen visas i Chatten men sparas aldrig och är inte Uppgifter.
_Avoid_: TODO-lista, uppgifter

**Permission-förfrågan**:
En fråga från Agenten om användaren godkänner en åtgärd. Bara användaren kan svara, och uteblivet svar räknas som avböjt.
_Avoid_: godkännande, bekräftelse

**Tool**:
En operation som appen exponerar via MCP mot Uppgiftslistan, t.ex. `create_task`.
_Avoid_: funktion, kommando, verktyg

**Förgodkänt tool**:
Ett Tool som Agenten är konfigurerad att använda utan Permission-förfrågan. Destruktiva Tools är aldrig förgodkända.
_Avoid_: auto-godkänt, whitelistat
