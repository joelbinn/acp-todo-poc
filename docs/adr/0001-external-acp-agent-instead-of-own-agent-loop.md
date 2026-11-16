# Extern ACP-agent i stället för egen agentloop

Status: accepted · 2026-11-12

Backend bygger ingen egen agentloop med Spring AI ChatClient eller LangChain4j (variant B). I stället startar den en färdig kodagent, Claude Code via `claude-agent-acp`, som subprocess och styr den över ACP. Syftet med POC:n är att pröva om färdiga agenter kan användas som utbytbar "hjärna" för en Java-app. Därför är det agentens inbyggda förmågor vi vill testa: planering, tool-val, permissions och streaming. Dessutom ska agenten kunna bytas med konfiguration (Gemini CLI).

## Konsekvenser

- Appen kräver Node.js och en installerad agent-CLI hos utvecklaren.
- Vi styr inte modellanrop, systemprompt eller kontexthantering. Agentens beteende påverkas bara genom toolbeskrivningar och `_meta`-options.
- Spring AI används bara för MCP-servern, inte för LLM-anrop.
