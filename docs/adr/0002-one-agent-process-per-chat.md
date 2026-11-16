# En agentprocess per Chatt

Status: accepted · 2026-11-12

Varje Chatt får en egen agentprocess med en enda ACP-session, i stället för att en delad process kör flera sessioner. Det ger fullständig isolering och enkel städning: när processen dödas är Chattens tillstånd borta, och processen kan inte påverka andra Chattar. Vi betalar med några sekunders starttid per Chatt och en övre gräns för antalet samtidiga Chattar (`max-sessions`, standard 3), eftersom varje process kostar minne.

## Konsekvenser

- `AgentSession` äger processens hela livscykel, inklusive `destroyForcibly()` efter `shutdown-grace`.
- Om vi senare går över till en delad process ändras bara adaptern bakom `IAgentSession`, men städnings- och isoleringsgarantierna måste då byggas om.
