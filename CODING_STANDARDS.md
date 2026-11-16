# Kodstandard (bedömningsregler)

Läses vid granskning. Mekaniska regler (importordning, oanvända importer, komplexitet högst 10) finns i `config/checkstyle.xml` och körs av `./mvnw verify` och pre-commit-hooken. Här står bara det en linter inte kan avgöra.

## Gränsen mellan lagren

- Arkitekturen följer [ADR-0004](docs/adr/0004-hexagonal-only-where-domain-logic-exists.md): portar och adaptrar bara där det finns domänlogik (`task`). ACP-koden (`agent`) får ligga i en adapter utan extra lager, så en utbruten `PermissionBroker` är inte ett krav.
- Beroenden pekar inåt: `domain` importerar aldrig `application`, `adapter` eller `web`.

## Opålitlig text från agenten

- Text som kommer från agenten (titlar, alternativnamn, svarstext) är opålitlig. Den får aldrig vara det enda en användare grundar ett godkännande på. Samtyckes-UI visar verktygstyp och råa indata, och knappar märks efter den betrodda `kind`-uppräkningen.
- Backend svarar aldrig själv på en Permission-förfrågan (NFR-7). Timeout, avbruten Tur och stängd Chatt skickas som `cancelled`.

## Händelser och samtidighet

- Varje lyckad ändring ger exakt en Uppgiftshändelse (DR-6). Ändringar som läser och sedan skriver samma Uppgift görs under ett lås, så att händelseordningen aldrig kan visa en borttagen Uppgift igen.
- Allt som skickas till en WebSocket går genom den skrivsynkroniserade sinken. Initial snapshot och händelser delar lås (`TaskBroadcaster`).

## Tester

- Tester mot en riktig fake-agent över SDK:ns in-memory-transport, inte mockar av SDK:n (NFR-8).
- Integrationstester delar en Spring-kontext och in-memory-listan. Ett test som skapar många Uppgifter städar efter sig, annars spräcker nästa flikes snapshot testklientens 8 KB-gräns för WebSocket-meddelanden.
