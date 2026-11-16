# Panelens ändringar går via REST, inte via Chatten

Status: accepted · 2026-11-16

Uppgiftspanelen var skrivskyddad och all ändring gick via Agenten och MCP. Nu kan användaren också skapa, byta titel på, slutföra och ta bort Uppgifter direkt (FR-16).

Panelen anropar REST-endpoints under `/api/tasks` (FR-17) som delegerar till `TaskService`, precis som MCP-tools gör. WebSocketen är knuten till en Chatt och en Agentsession, och direkta ändringar hör inte dit. Panelen uppdateras som förut av Uppgiftshändelser via `TaskBroadcaster`, så ändringar från panelen, agenten och MCP-klienter syns lika i alla flikar.

Appen saknar inloggning, så en annan webbplats i användarens webbläsare kunde annars skicka ändrande anrop till `localhost:8080` (CSRF). `RestOriginFilter` kräver därför `Origin` ur `poc.web.allowed-origins` och `Content-Type: application/json` på alla ändrande anrop (NFR-10).

## Consequences

- Ny driving adapter `task/adapter/rest` bredvid `task/adapter/mcp`. Domänen och `TaskService` är oförändrade i sin struktur.
- Ny domänoperation `rename` och Uppgiftshändelsen `TaskRenamed` (DR-7). Agenten får motsvarande tool `rename_task`.
- Användarens egen borttagning kräver ingen Permission-förfrågan. Den gäller bara Agentens `delete_task` (FR-15, NFR-7).
- Senast skrivna vinner. Ingen versionskontroll behövs utan persistens.
