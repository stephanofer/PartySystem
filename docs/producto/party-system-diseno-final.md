# PartySystem - Diseno Final Cerrado

Este documento cierra el diseno formal de PartySystem para HERA Network antes de iniciar la implementacion.

El objetivo es dejar una fuente de verdad clara para el equipo: que debe construir PartySystem, que no debe tocar, como se integra con modalidades, como debe comportarse ante edge-cases, y que decisiones ya quedaron tomadas durante la etapa de diseno.

## Estado Del Documento

| Campo | Valor |
| --- | --- |
| Proyecto | PartySystem |
| Plataforma principal | Velocity |
| Estado | Diseno cerrado para implementacion |
| Producto | Producto completo y profesional |
| Fuente base | `partysystem.md`, FriendsSystem, ProxySettings, LuckPerms, Velocity, Adventure, Cloud, BoostedYAML, Caffeine y CraftKit Redis |

## Decision Principal

PartySystem es un sistema social de red. No pertenece a una modalidad concreta.

Debe vivir en Velocity porque una party puede tener jugadores distribuidos entre lobby global, lobbies de modalidad, servidores de arena, reconnect flows, spectator flows o fallback tecnico.

La regla central es:

```text
PartySystem sabe quien esta con quien.
La modalidad decide que puede hacer ese grupo dentro del juego.
```

## Responsabilidades

PartySystem debe encargarse de:

| Area | Responsabilidad |
| --- | --- |
| Lifecycle | Crear parties, invitar, aceptar, denegar, retirar invitaciones, salir, expulsar, disolver y transferir leader. |
| Estado runtime | Mantener la vista actual de parties, miembros, leader, roles, invitaciones y server actual. |
| UX social | Comandos, ayuda clickable, autocompletado contextual, feedback configurable y party chat. |
| Redis | Publicar snapshots runtime read-only para modalidades. |
| Party follow | Mover miembros seguidores solo hacia destinos sociales permitidos en configuracion. |
| Idioma y estilos | Usar ProxySettings para idioma, bandera, nick style y chat style. |
| Prefixes | Usar LuckPerms para resolver prefix y grupo primario cuando sea necesario. |
| Debug | Entregar logs profesionales por categorias para detectar errores y explicar decisiones internas. |

PartySystem no debe encargarse de:

| Area | Motivo |
| --- | --- |
| Matchmaking | Lo decide la modalidad. |
| Reservar arenas | Lo decide la modalidad/runtime de juego. |
| Crear waiting rooms | Lo decide la modalidad/runtime de juego. |
| Asignar equipos | Lo decide la modalidad. |
| Validar ranked | Lo decide la modalidad. |
| Mover a match, arena, reconnect o spectator | Requiere admission/policy de modalidad. |
| Partir parties entre matches | Rompe integridad competitiva. |
| Leer reglas internas de cada modalidad | PartySystem debe mantenerse desacoplado. |

## Decision Sobre MySQL

PartySystem no usara MySQL para el lifecycle de parties.

Esta decision no reduce calidad. Al contrario: evita usar una herramienta durable para un problema runtime temporal.

Una party vive poco y cambia mucho. Sus datos principales no son historicos; son estado vivo de red.

| Dato | Naturaleza | Storage correcto |
| --- | --- | --- |
| Miembros actuales | Temporal | Memoria + Redis snapshot |
| Leader actual | Temporal | Memoria + Redis snapshot |
| Invitaciones | Temporal con expiracion | Caffeine/local runtime, con TTL |
| Party chat toggle | Temporal por sesion | Memoria/Caffeine |
| Server actual de miembros | Runtime | Velocity + Redis snapshot |
| Snapshot para modalidades | Runtime compartido | Redis con TTL |
| Historial, analytics o preferencias persistentes | Durable, si se agrega como feature futura | MySQL |

MySQL no se usa porque:

| Razon | Impacto |
| --- | --- |
| El estado cambia muy seguido | Evita writes constantes por join, leave, kick, transfer y server change. |
| Las parties expiran naturalmente | Redis y Caffeine manejan TTL mejor que tablas con cleanup. |
| Las modalidades necesitan frescura | Redis entrega snapshots runtime rapidos y con expiracion. |
| No hay necesidad de historial para funcionar | Si una party runtime desaparece al reiniciar proxy, no hay relacion durable que restaurar. |
| Menos complejidad operacional | Sin migraciones, tablas, repositorios SQL ni inconsistencias persistidas innecesarias. |

Si en el futuro se agregan features durables como historial de parties, preferencias permanentes, estadisticas sociales o auditoria persistida, esas features podran usar MySQL como modulo separado. No forman parte del core runtime de PartySystem.

## Arquitectura De Paquetes

La arquitectura debe ser simple, legible y orientada por responsabilidad. No se debe introducir sobreingenieria.

```text
src/main/java/com/stephanofer/partySystem/
  PartySystem.java

  config/
    PluginConfig.java
    Messages.java
    Language.java

  command/
    PartyCommands.java
    PartySuggestionCache.java

  domain/
    Party.java
    PartyMember.java
    PartyRole.java
    PartyMemberState.java
    PartyInvite.java

  service/
    PartyService.java
    InviteService.java
    PartyChatService.java
    PartyFollowService.java
    PartySnapshotService.java
    FeedbackService.java

  integration/
    ProxySettingsGateway.java
    LuckPermsGateway.java
    RedisPartySnapshotStore.java

  listener/
    PlayerConnectionListener.java
    PlayerServerListener.java
    PlayerChatListener.java

  follow/
    FollowDestinationRegistry.java
    MovementCauseTracker.java

  debug/
    DebugLogger.java

  util/
    DurationParser.java
    MiniMessageEscaper.java
```

### Rol De Cada Paquete

| Paquete | Contenido |
| --- | --- |
| `config` | Carga de `config.yml`, mensajes por idioma y parsing de opciones. |
| `command` | Comandos Cloud Velocity y sugerencias contextuales. |
| `domain` | Modelo de party sin dependencia de Velocity/Redis. |
| `service` | Casos de uso principales y reglas de negocio. |
| `integration` | Adaptadores hacia ProxySettings, LuckPerms y Redis. |
| `listener` | Eventos Velocity. |
| `follow` | Destinos permitidos, party follow y proteccion anti-loop. |
| `debug` | Logs estructurados por categoria y razon. |
| `util` | Utilidades pequenas, solo si realmente se reutilizan. |

## Estado Runtime

El estado principal de PartySystem vive en memoria del plugin Velocity.

Estructuras propuestas:

```text
partyById: Map<String, Party>
partyIdByPlayer: Map<UUID, String>
incomingInvitesByTarget: Map<UUID, List<PartyInvite>>
outgoingInvitesBySender: Map<UUID, List<PartyInvite>>
partyChatEnabled: Cache<UUID, Boolean>
movementCauses: Cache<UUID, MovementMarker>
```

Uso de Caffeine:

| Cache | Objetivo | TTL sugerido |
| --- | --- | --- |
| Sugerencias | Evitar recomputar listas por tab-complete | `5s` |
| Party chat toggle | Estado temporal del toggle | Hasta disconnect o TTL largo |
| Movement causes | Anti-loop de party follow | `5s` |
| LuckPerms snapshot | Evitar cargar prefix repetidamente | `2m` |

Las invitaciones deben expirar con TTL configurable. Al expirar, deben desaparecer de las sugerencias y comportarse como inexistentes.

## Roles

Los roles cerrados para implementacion son:

| Rol | Permisos |
| --- | --- |
| `LEADER` | Invitar, expulsar, transferir liderazgo y disolver party. |
| `MEMBER` | Ver party, usar party chat y salir de la party. |

No se implementara `OFFICER` en este diseno cerrado. Agrega superficie de permisos y edge-cases sin necesidad actual.

## Lifecycle De Party

### Crear Party

Una party no se crea manualmente. Una party existe solo cuando hay al menos dos jugadores.

Flujo cerrado:

1. Un jugador usa `/party invite <player>`.
2. Si el sender no tiene party, solo se registra la invitacion pendiente.
3. Cuando el target acepta, se materializa la party con sender como `LEADER` y target como `MEMBER`.
4. Si el sender ya tiene party, el target entra a esa party al aceptar.

No existe `/party create`. Evita parties fantasma de un solo jugador y reduce edge-cases de UX y lifecycle.

### Invitaciones

Las invitaciones deben:

- Notificar al target.
- Tener expiracion configurable.
- Aparecer en autocomplete de `accept` y `deny`.
- Aparecer en autocomplete de `withdraw` para el sender.
- Ser retirables por el sender.
- Ser rechazables por el target.
- Limpiarse cuando sender o target entra en estados incompatibles.

### Aceptar Invitacion

Al aceptar:

1. Se valida que la invitacion exista y no haya expirado.
2. Se valida que el target no este en otra party.
3. Se valida que el sender siga online y en estado valido.
4. Se crea o actualiza la party.
5. Se publica snapshot Redis.
6. Se notifica a sender, target y miembros existentes.
7. No se mueve automaticamente al nuevo miembro si el leader esta en gameplay no permitido en `follow.destinations`.

### Salir, Expulsar Y Disolver

| Accion | Regla |
| --- | --- |
| `leave` de member | Sale, se notifica y se actualiza snapshot si quedan 2+ miembros. Si queda 1 miembro, se disuelve. |
| `leave` de leader | Transfiere leader si quedan 2+ miembros. Si queda 1 miembro, se disuelve. |
| `kick` | Solo `LEADER`; no puede kickearse a si mismo desde `kick`. |
| `disband` | Solo `LEADER`; elimina party y snapshots. |
| Disconnect de member | Sale de la party y actualiza snapshot si quedan 2+ miembros. Si queda 1 miembro, se disuelve. |
| Disconnect de leader | Transfiere leader al miembro online mas antiguo si quedan 2+ miembros; si queda 1 miembro, se disuelve. |

Regla central:

```text
Party minima = 2 jugadores.
```

## Comandos

Los comandos deben ser configurables como en FriendsSystem.

```yaml
commands:
  primary: "party"
  aliases:
    - "p"
    - "parties"
  party-chat-aliases:
    - "pc"
  suggestions:
    cache-ttl: "5s"
    empty-input-max-results: 20
    filtered-max-results: 50
```

Arbol de comandos:

| Comando | Uso |
| --- | --- |
| `/party` | Muestra ayuda clickable. |
| `/party invite <player>` | Invita a un jugador online. Si acepta y el sender no tenia party, crea una party de 2 jugadores. |
| `/party accept <player>` | Acepta invitacion recibida. |
| `/party deny <player>` | Rechaza invitacion recibida. |
| `/party withdraw <player>` | Retira invitacion enviada. |
| `/party pending` | Lista invitaciones recibidas y enviadas. |
| `/party list` | Lista miembros actuales. |
| `/party leave` | Sale de la party. |
| `/party kick <player>` | Expulsa miembro, solo leader. |
| `/party transfer <player>` | Transfiere liderazgo. |
| `/party disband` | Disuelve party, solo leader. |
| `/party chat <message>` | Envia mensaje a la party. |
| `/party togglechat` | Alterna chat de party para chat normal. |
| `/pc <message>` | Alias directo de party chat. |

## Autocomplete Profesional

El autocomplete debe ser contextual, rapido y util. Cloud Velocity se usara como en FriendsSystem.

| Argumento | Fuente | Regla de performance |
| --- | --- | --- |
| `/party invite <player>` | Jugadores online de Velocity | Excluir self y miembros actuales. Sin Redis/MySQL. |
| `/party accept <player>` | Invitaciones entrantes del jugador | Cache Caffeine corta. |
| `/party deny <player>` | Invitaciones entrantes del jugador | Cache Caffeine corta. |
| `/party withdraw <player>` | Invitaciones salientes del jugador | Cache Caffeine corta. |
| `/party kick <player>` | Miembros actuales de la party | Memoria local. Excluir leader si aplica. |
| `/party transfer <player>` | Miembros actuales online | Memoria local. Excluir self. |

Reglas obligatorias:

- Filtrar por prefijo escrito antes de aplicar limites.
- Ordenar resultados por nombre visible o username.
- No consultar MySQL por tab-complete.
- No bloquear con `join()` ni `get()`.
- Si una fuente falla, devolver sugerencias vacias sin romper el comando.
- Invalidar caches de sugerencias al aceptar, denegar, retirar, expirar invitacion, entrar o salir de party.

## Mensajes, Idioma Y Feedback

PartySystem debe replicar el enfoque profesional de FriendsSystem:

| Area | Decision |
| --- | --- |
| Mensajes | `messages/es.yml` y `messages/en.yml`. |
| Formato | MiniMessage en templates configurables. |
| Idioma | ProxySettings resuelve idioma efectivo del jugador. |
| Click/Hover | Ayuda e invitaciones deben usar click/hover actions. |
| Feedback | Sistema por acciones con outputs configurables. |

Modelo de feedback:

```yaml
feedback:
  actions:
    invite-received:
      outputs:
        - type: CHAT
          message: "invite.received"
        - type: ACTION_BAR
          message: "invite.actionbar"
        - type: SOUND
          sound: "minecraft:block.note_block.pling"
          source: "MASTER"
          volume: 1.0
          pitch: 1.4
    party-disbanded:
      outputs:
        - type: TITLE
          title: "party.disbanded-title"
          subtitle: "party.disbanded-subtitle"
```

Tipos soportados:

| Tipo | Comportamiento |
| --- | --- |
| `CHAT` | Envia mensaje al chat. |
| `ACTION_BAR` | Envia actionbar. |
| `TITLE` | Muestra title/subtitle. |
| `SOUND` | Reproduce sonido Adventure. |
| `NONE` | Desactiva todos los outputs para esa accion. |

`CENTER` no se implementa. Centrar chat correctamente depende de fuente, escala, cliente y resource packs. Una aproximacion mala seria peor que no tenerlo.

## Integracion Con ProxySettings

PartySystem debe usar ProxySettings para todo contenido visible de jugadores.

Usos obligatorios:

| Necesidad | API esperada |
| --- | --- |
| Idioma | `resolvedLanguage(playerId, player.getEffectiveLocale())` |
| Cargar varios jugadores | `loadMany(Collection<UUID>)` |
| Nick style | `formattedNick(playerId, username)` |
| Bandera | `countryFlag(playerId)` o resolver equivalente |
| Chat style | `formatChatMessage(playerId, Component.text(message))` |

Formato de identidad configurable:

```yaml
display:
  player-identity-format: "<player_prefix><country_flag> <player_nick>"
```

Todo lugar donde aparezca un jugador debe usar `<player_identity>` o resolvers equivalentes.

## Integracion Con LuckPerms

LuckPerms se usa para resolver prefix y grupo primario.

Estrategia cerrada:

| Caso | Estrategia |
| --- | --- |
| Jugador online con user cargado | `getUser(uuid)` y `getCachedData().getMetaData().getPrefix()`. |
| Jugador online pero user no cargado | `loadUser(uuid)` async. |
| Jugador offline o snapshot faltante | `loadUser(uuid)` async solo si realmente hace falta. |
| LuckPerms no disponible | Prefix vacio y warning. |
| Error de LuckPerms | Prefix vacio, warning y continuar. |

No se debe bloquear el flujo con `join()` o `get()`. LuckPerms `loadUser` puede hacer I/O y debe usarse con `CompletableFuture`.

En PartySystem casi todas las interacciones reales son con jugadores online, asi que el caso offline debe ser excepcional: invitaciones pendientes, snapshots recientes o renderizado de datos ya capturados.

## Party Chat

Party chat debe funcionar de dos formas:

| Forma | Comportamiento |
| --- | --- |
| `/party chat <message>` | Envia mensaje directo a la party. |
| `/pc <message>` | Alias directo. |
| `/party togglechat` | Activa/desactiva captura del chat normal. |

Cuando `togglechat` esta activo:

1. El jugador escribe en chat normal.
2. Velocity dispara `PlayerChatEvent`.
3. PartySystem detecta que `partyChatEnabled` esta activo.
4. PartySystem cancela el chat normal con `PlayerChatEvent.ChatResult.denied()`.
5. PartySystem envia el mensaje a los miembros de la party desde Velocity.
6. El servidor Paper backend no recibe ese mensaje como chat publico normal.

Seguridad de texto:

```java
Component raw = Component.text(message);
Component styled = proxySettings.formatChatMessage(playerId, raw).orElse(raw);
```

El texto escrito por jugadores nunca debe parsearse como MiniMessage.

Formato configurable:

```yaml
display:
  party-chat-format: "<dark_gray>[</dark_gray><gradient:#8ec5ff:#e0c3fc>Party</gradient><dark_gray>]</dark_gray> <player_identity><gray>:</gray> <message>"
```

## Party Follow

Party follow solo aplica a navegacion social/global. No aplica a gameplay.

Configuracion conceptual:

```yaml
follow:
  enabled: true
  anti-loop-ttl: "5s"
  destinations:
    - global-lobby-01
    - bedwars-lobby-01
    - skywars-lobby-01
```

Reglas:

| Cambio detectado | Follow |
| --- | --- |
| Lobby global a lobby de modalidad | Si, si el destino esta en `follow.destinations`. |
| Lobby de modalidad a arena | No. |
| Lobby de modalidad a waiting room | No. |
| Arena a lobby por fin de match | No por defecto. |
| Reconnect | No. |
| Spectator | No. |
| Fallback/kick tecnico | No. |
| Movimiento causado por PartySystem | No, por anti-loop. |

### Anti-Loop

Anti-loop evita que PartySystem reaccione a movimientos que el propio PartySystem causo.

Ejemplo:

```text
1. El leader entra a bedwars-lobby-01.
2. PartySystem mueve a los followers.
3. Velocity dispara ServerConnectedEvent para esos followers.
4. PartySystem debe ignorar esos eventos porque fueron causados por PartySystem.
```

Sin anti-loop pueden aparecer movimientos duplicados, mensajes duplicados, cascadas de follow o loops con plugins de fallback/redireccion.

La solucion es marcar temporalmente al jugador movido con el destino esperado:

```text
movementCauses[playerId] = targetServerId, ttl=5s
```

La marca se consume solo si el `ServerConnectedEvent` coincide con ese destino. Si el jugador cambia manualmente a otro servidor dentro del TTL, ese movimiento no debe tratarse como causado por PartySystem.

Si llega un evento de cambio de servidor para ese mismo jugador y destino, PartySystem consume la marca y no dispara follow por ese evento.

## Contrato Redis Para Modalidades

PartySystem publica una vista pequena, estable y read-only. Las modalidades no deben acceder al modelo interno del plugin.

Keys finales usando CraftKit Redis:

```text
redis.key("party", "player", playerUuid)
redis.key("party", "snapshot", partyId)
redis.key("party", "version", partyId)
```

Con `keyPrefix = hera` y `environment = prod`:

```text
hera:prod:party:player:<uuid>
hera:prod:party:snapshot:<partyId>
hera:prod:party:version:<partyId>
```

Snapshot:

```json
{
  "partyId": "party_abc123",
  "leaderId": "uuid-leader",
  "members": [
    {
      "playerId": "uuid-leader",
      "role": "LEADER",
      "state": "ONLINE",
      "transferable": true,
      "serverId": "bedwars-lobby-01"
    }
  ],
  "createdAt": "2026-07-05T00:00:00Z",
  "updatedAt": "2026-07-05T00:00:10Z",
  "version": 42
}
```

TTL recomendado:

| Key | TTL |
| --- | --- |
| `party:player:<uuid>` | `120s` |
| `party:snapshot:<partyId>` | `120s` |
| `party:version:<partyId>` | `120s` |

Reglas:

- Actualizar snapshot cuando cambien miembros, leader, estado transferible o server actual.
- Borrar `party:player:<uuid>` cuando el jugador salga.
- Borrar snapshot/version cuando la party se disuelva.
- Renovar TTL con cambios y heartbeat.
- Usar `unlink` para cleanup cuando sea posible.
- Si Redis falla, mantener runtime local, registrar error y reintentar en el siguiente cambio/heartbeat.

## Flujo De Modalidades

Cuando una modalidad necesita saber si un jugador esta en party:

1. Lee `party:player:<playerId>`.
2. Si no existe, trata al jugador como solo-player.
3. Si existe, obtiene `partyId`.
4. Lee `party:snapshot:<partyId>`.
5. Congela ese snapshot para la accion actual.
6. Valida party size, policy, ranked, capacidad y restricciones.
7. Si todo es valido, crea admissions y mueve jugadores con su propio runtime.

PartySystem no decide si la party puede entrar a una cola. Solo expone el grupo.

## Debug Profesional

PartySystem debe tener un sistema de debug auditable por categorias.

Configuracion:

```yaml
debug:
  enabled: false
  include-stacktraces: false
  categories:
    lifecycle: true
    invites: true
    commands: true
    chat: true
    follow: true
    snapshots: true
    redis: true
    permissions: true
    autocomplete: false
```

Categorias:

| Categoria | Registra |
| --- | --- |
| `lifecycle` | Party creada, disuelta, miembro entra/sale, leader transferido. |
| `invites` | Invitacion creada, expirada, aceptada, denegada o retirada. |
| `commands` | Comando rechazado y razon logica. |
| `chat` | Party chat enviado o rechazado. |
| `follow` | Movimiento follow ejecutado o ignorado. |
| `snapshots` | Snapshot publicado, borrado, stale o inconsistente. |
| `redis` | Ping, writes, unlink, errores y recuperacion. |
| `permissions` | LuckPerms no disponible, prefix fallback, permisos denegados. |
| `autocomplete` | Cache hit/miss, fuente usada y cantidad de candidatos. |

Razones normalizadas:

```text
not-in-party
not-party-leader
invite-expired
invite-not-found
target-already-in-party
member-not-found
destination-not-allowed
movement-caused-by-party-system
member-offline
member-not-transferable
already-at-destination
redis-write-failed
snapshot-stale
permission-denied
luckperms-unavailable
```

Ejemplo de log util:

```text
Party follow skipped player=Cristian uuid=<uuid> party=party_abc target=bedwars-arena-01 reason=destination-not-allowed
```

La meta del debug no es imprimir mas. Es explicar rapidamente por que el sistema tomo una decision.

## Configuracion Inicial

Archivo principal:

```text
src/main/resources/config.yml
```

Estructura base:

```yaml
config-version: 3

redis:
  host: "127.0.0.1"
  port: 6379
  database: 0
  username: ""
  password: ""
  ssl: false
  key-prefix: "hera"
  environment: "prod"
  server-id: "velocity-1"

party:
  invite-expiration: "60s"
  transfer-leader-on-disconnect: true
  chat-max-length: 512

limits:
  party-size:
    default: 4
    permissions:
      - permission: "partysystem.limit.default"
        limit: 4
      - permission: "partysystem.limit.vip"
        limit: 6
      - permission: "partysystem.limit.elite"
        limit: 8
      - permission: "partysystem.limit.hera"
        limit: 12

commands:
  primary: "party"
  aliases:
    - "p"
    - "parties"
  party-chat-aliases:
    - "pc"
  suggestions:
    cache-ttl: "5s"
    empty-input-max-results: 20
    filtered-max-results: 50

cooldowns:
  invite: "750ms"
  list: "750ms"
  chat: "250ms"
  togglechat: "1s"

cache:
  movement-cause-ttl: "5s"
  luckperms-snapshot-ttl: "2m"

snapshots:
  ttl: "120s"
  heartbeat: "30s"

display:
  player-identity-format: "<player_prefix><country_flag> <player_nick>"
  party-chat-format: "<dark_gray>[</dark_gray><gradient:#8ec5ff:#e0c3fc>Party</gradient><dark_gray>]</dark_gray> <player_identity><gray>:</gray> <message>"

follow:
  enabled: true
  anti-loop-ttl: "5s"
  destinations: []

feedback:
  actions: {}

debug:
  enabled: false
```

## Dependencias Previstas

| Dependencia | Uso |
| --- | --- |
| Velocity API | Plugin, eventos, players, server movement y chat interception. |
| Cloud Velocity | Comandos y autocomplete. |
| Cloud Minecraft Extras | Solo si mejora exception UX sin complejidad innecesaria. |
| Adventure MiniMessage | Render de mensajes configurables. |
| BoostedYAML | Configs y mensajes versionados. |
| Caffeine | Caches locales de alto rendimiento. |
| CraftKit Redis | Snapshots runtime, TTL, cleanup y key naming. |
| ProxySettings | Idioma, bandera, nick style y chat style. |
| LuckPerms API | Prefix y grupo primario. |

## Edge Cases Cerrados

| Caso | Resultado correcto |
| --- | --- |
| Invite a si mismo | Rechazar. |
| Invite a offline | Rechazar en el producto actual. |
| Invite duplicada | Mostrar que ya existe invitacion. |
| Invite cruzada | Indicar que puede aceptar la invitacion recibida. |
| Accept sin invitacion | Mensaje especifico. |
| Accept con invitacion expirada | Tratar como inexistente y limpiar. |
| Accept estando en otra party | Rechazar. |
| Sender se desconecta antes del accept | Rechazar la aceptacion. |
| Target acepta mientras leader esta en arena | Unir a la party, pero no mover automaticamente a gameplay. |
| Leave de member | Sale y actualiza snapshot. |
| Leave de leader con miembros | Transferir leader al miembro online mas antiguo. |
| Leave de leader sin miembros | Disolver party. |
| Kick a no miembro | Rechazar. |
| Kick al leader | Rechazar. |
| Transfer a no miembro | Rechazar. |
| Disband por member | Rechazar. |
| Party chat sin party | Rechazar. |
| Toggle activo y party se disuelve | Limpiar toggle. |
| Mensaje vacio | Rechazar. |
| Mensaje demasiado largo | Rechazar segun limite configurable. |
| Redis caido | Mantener runtime local, loggear error y reintentar. |
| Leader cambia a lobby permitido | Mover followers transferibles. |
| Leader cambia a arena | No mover followers. |
| Movimiento causado por PartySystem | Ignorar por anti-loop. |
| Follower ya esta en destino | No mover y loggear razon. |
| Follower offline | No mover. |
| Snapshot stale | Modalidad debe rechazar fail-safe. |
| Party cambia despues de click de modalidad | La modalidad usa snapshot congelado. |

## No Hacer

- No usar MySQL para el lifecycle runtime de parties.
- No mover jugadores a arenas, waiting rooms, matches, reconnect o spectator desde PartySystem.
- No duplicar reglas de modalidades.
- No hacer que lobby global lea Redis de parties.
- No confiar en plugin messaging para movimientos criticos.
- No dejar snapshots sin TTL.
- No bloquear eventos/comandos con `join()` o `get()`.
- No parsear texto de usuario como MiniMessage.
- No implementar roles extra sin necesidad real.
- No implementar `CENTER` como aproximacion pobre.

## Orden De Implementacion Recomendado

1. Configuracion Gradle y dependencias.
2. `PluginConfig`, `Messages`, resources `config.yml`, `messages/es.yml`, `messages/en.yml`.
3. `ProxySettingsGateway` y `LuckPermsGateway`.
4. Modelo `domain`.
5. `PartyService` e `InviteService`.
6. `FeedbackService`.
7. Comandos Cloud y autocomplete.
8. Party chat por comando y `PlayerChatEvent`.
9. Redis snapshots y heartbeat.
10. Party follow, destinos permitidos y anti-loop.
11. Listeners de login, disconnect y server connected.
12. Debug profesional por categorias.
13. Tests de config, mensajes, lifecycle, edge-cases y snapshots.

## Decision Final

PartySystem se implementara como un producto completo de red en Velocity, con estado runtime en memoria, snapshots Redis con TTL para modalidades, UX configurable al nivel de FriendsSystem, integracion completa con ProxySettings/LuckPerms, autocomplete contextual de alto rendimiento, party chat interceptado desde Velocity, party follow limitado a destinos sociales y un sistema de debug profesional.

La frontera final queda cerrada:

```text
PartySystem gestiona la experiencia social de party.
Redis expone la vista runtime confiable.
Las modalidades validan gameplay y mueven jugadores a gameplay.
```
