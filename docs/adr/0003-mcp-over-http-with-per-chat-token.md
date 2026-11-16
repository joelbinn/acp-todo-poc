# MCP över Streamable HTTP mot den egna appen, med en token per Chatt

Status: accepted · 2026-11-12

Appen exponerar sina Tools som en MCP-server på `/mcp` (Streamable HTTP) i samma process. Agenten får servern som en HTTP-server i `session/new` med headern `Authorization: Bearer <token>`. Token är slumpad per Chatt och ogiltigförklaras när Chatten stängs. Den slår också upp chatt-id, så att loggar och tools vet vilken Chatt ett anrop kommer från. HTTP valdes framför stdio eftersom Tools och uppgiftshändelser då lever i samma process och samma minne som WebSocket-delen. Det krävs ingen extra process, och servern kan dessutom anropas av andra MCP-klienter (MCP Inspector). SSE används inte, eftersom det är deprecated sedan Spring AI 2.0.

## Considered Options

- **stdio-MCP-server som egen process:** skulle kräva IPC tillbaka till appen för att dela Uppgiftslistan.
- **En token för alla Chattar:** gör det omöjligt att koppla ett tool-anrop till en Chatt, och token överlever Chatten.

## Consequences

- Hela POC:n hänger på att agenten faktiskt ansluter till HTTP-MCP-servrar från `session/new`. Spiken (steg 3) avgör det, och den stannar och rapporterar om det inte fungerar.
- Reserven är en tunn stdio-brygga som agenten startar och som proxar till `/mcp`.
