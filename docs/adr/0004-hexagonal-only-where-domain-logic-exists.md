# Hexagonal struktur bara där det finns domänlogik

Status: accepted · 2026-11-12

Uppgiftslistan byggs med ports & adapters:

- `task/domain`: Uppgift som Aggregate Root, händelserna `TaskCreated`, `TaskCompleted` och `TaskDeleted`, samt portarna `ITaskRepository` och `ITaskEventPublisher`.
- `task/application`: `TaskService`.
- Adaptrar för MCP (`TaskTools`), minne och Spring-events.

ACP-integrationen saknar däremot egen affärslogik, och den är i praktiken översättning mellan protokoll. Den får därför bara en port, `IAgentSession`, med en ACP-adapter bakom och inget eget domänlager. Detta avviker både från specens första paketindelning per teknisk funktion och från en strikt hexagonal struktur för allt. Ett påhittat domänlager för ACP skulle bara bli tomma lager runt SDK-anrop.

## Consequences

- Varje Uppgift är ett eget aggregat, eftersom inga invarianter spänner över flera Uppgifter. Löpnumret delas ut av `ITaskRepository`.
- Aggregatet skapar händelser, och `TaskService` publicerar dem. Ett repository publicerar aldrig händelser.
- Porten `IAgentSession` är också den plats där ACP-SDK:ns 0.x-API får ändras utan att resten av appen påverkas.
