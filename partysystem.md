# PartySystem

Este documento define el diseno final de integracion entre PartySystem y las modalidades de HERA Network.

El objetivo es que el equipo de PartySystem sepa exactamente que debe construir y exponer para que cualquier modalidad pueda entender parties, mover miembros cuando corresponde y respetar los flujos competitivos sin acoplarse a la logica interna de cada juego.

## Decision Principal

PartySystem es un sistema de red, no de una modalidad concreta.

Debe vivir principalmente en Velocity porque una party puede tener miembros distribuidos entre distintos servidores: lobby global, lobbies de modalidad, arena servers, spectators o reconnect flows.

PartySystem es responsable de:

- crear y mantener parties;
- manejar invites, accept, leave, kick, disband y leader change;
- saber quien pertenece a quien;
- publicar snapshots runtime en Redis;
- aplicar party-follow en navegacion social/global;
- mover miembros seguidores cuando el leader entra a destinos followables.

PartySystem no es responsable de:

- decidir matchmaking;
- reservar arenas;
- crear waiting rooms;
- asignar equipos dentro de una partida;
- permitir o rechazar ranked;
- permitir o rechazar spectator de match;
- saltarse admissions o validaciones de una modalidad.

Regla corta:

```text
PartySystem sabe quien esta con quien.
La modalidad decide que puede hacer ese grupo dentro del juego.
```

## Planos De Movimiento

Hay dos tipos de movimiento y no deben mezclarse.

| Movimiento | Owner | Regla                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| --- | --- |----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Navegacion social/global | PartySystem | Follow del leader hacia por ejemplo lobbies permitidos.                                                                                                                                                                                                                                                                                                                                                                                            |
| Gameplay de modalidad | Modalidad/runtime de juego | existe un plugin de velocity que es el encargado de mover a los jugadores por ejemplo a las waiting room, match, reconnect, spectator y return controlado, y esas cosas entonces sirve para todas las modadleis basicametn lo que hace esque un jugador cuand ova a entar a una arena esa erena esta en otro servidor y este plugin es necargado de moverlo obivamnte pasnado una serie de cosas pero el mueve a los jugadores y demas             |
| Gameplay de modalidad | Modalidad/runtime de juego | existe un plugin de velocity que es el encargado de mover a los jugadores por ejemplo a las waiting room, match, reconnect, spectator y return controlado, y esas cosas entonces sirve para todas las modadleis basicametn lo que hace esque un jugador cuand ova a entar a una arena esa erena esta en otro servidor y este plugin es necargado de moverlo obivamnte pasnado una serie de cosas pero el mueve a los jugadores y demas             |

Esto evita que PartySystem mueva jugadores a arenas o partidas sin que la modalidad haya validado reglas, capacidad, party policy, ranked integrity o admissions.

## Lobby Global A Lobby De Modalidad

El plugin del lobby global no debe saber nada de parties.

Su trabajo es simple:

1. Mostrar menus.
2. Resolver el destino elegido.
3. Mover solamente al jugador que hizo click.

Ejemplo:

```text
Stephano clickea BedWars en el lobby global.
El plugin del lobby global mueve solo a Stephano a bedwars-lobby-01.
```

Despues entra PartySystem:

1. PartySystem escucha en Velocity que Stephano cambio de server.
2. Detecta que `bedwars-lobby-01` es un destino followable.
3. Verifica que Stephano es leader o que la party policy permite follow.
4. Verifica que Cristian esta en la party y es transferible.
5. Mueve a Cristian a `bedwars-lobby-01`.

Este flujo no necesita admission de modalidad porque todavia no se esta entrando a una queue, waiting room, match o spectator. Es solo navegacion social hacia un lobby.

## Destinos Followables

PartySystem no debe seguir cualquier cambio de servidor del leader.

Debe tener una configuracion o registry de destinos followables. Solo esos destinos pueden activar party-follow.

Ejemplo conceptual:

```yaml
follow-destinations:
  bedwars-lobby-01:
    type: modality_lobby
    game: bedwars
  skywars-lobby-01:
    type: modality_lobby
    game: skywars
```

Reglas:

- follow permitido hacia lobbies globales o lobbies de modalidad configurados;
- follow prohibido hacia arena servers;
- follow prohibido hacia waiting rooms si el movimiento lo inicio una modalidad;
- follow prohibido hacia reconnect, spectator o fallback tecnico;
- follow debe tener proteccion anti-loop para no reaccionar a movimientos causados por el propio PartySystem o algo asi.
- De esas cosas prohibidas que menciono obivamnte es porque el otro plugin de velocity de las modaldies se encarga de hacer eso 

## Como Evitar Duplicar Movimientos De Modalidad

Caso peligroso:

```text
Stephano esta en bedwars-lobby-01.
Stephano clickea Unranked BedWars 2v2.
La modalidad mueve a Stephano a bedwars-arena-01.
PartySystem ve el cambio y tambien intenta mover a Cristian.
```

Eso NO debe pasar.

Cuando el destino es un arena server o un flujo de gameplay, PartySystem debe ignorar el cambio. La modalidad ya esta coordinando ese movimiento con su propio sistema de admissions.

Tabla final:

| Cambio detectado | PartySystem hace follow? | Motivo |
| --- | --- | --- |
| Lobby global -> lobby de modalidad | Si | Navegacion social. |
| Lobby de modalidad -> arena/waiting room | No | La modalidad controla la entrada. |
| Arena -> lobby de modalidad por cierre de match | No por defecto | La modalidad controla cleanup/return. |
| Reconnect a match | No | La modalidad valida reconnect. |
| Spectate match | No | La modalidad valida spectator policy. |
| Fallback/kick tecnico | No | Evita loops y estados corruptos. |

PartySystem debe clasificar cada cambio antes de mover followers.

Criterios de clasificacion:

1. El destino debe existir en `follow-destinations`.
2. El destino debe tener tipo social, por ejemplo `global_lobby` o `modality_lobby`.
3. El destino no puede tener rol de gameplay, por ejemplo `arena`, `waiting_room`, `match`, `spectator` o `reconnect`.
4. El cambio no puede haber sido causado por el propio PartySystem dentro de una ventana anti-loop.
5. Si existe una marca/admission activa de modalidad para ese jugador y destino, PartySystem debe ignorar el cambio.

Con esto, PartySystem no necesita entender como funciona BedWars o SkyWars. Solo necesita saber si el servidor destino es followable o si pertenece a un flujo de gameplay.

## Modalidad A Waiting Room O Match

Cuando el leader ya esta en el lobby de una modalidad y clickea una queue, PartySystem no mueve a nadie por su cuenta.

Ejemplo:

```text
Stephano clickea Unranked BedWars 2v2.
```

Flujo esperado:

1. La modalidad recibe la intencion de jugar.
2. La modalidad consulta si Stephano esta en party mediante el contrato de snapshot.
3. PartySystem no decide si esa party puede jugar; solo expone el grupo.
4. La modalidad valida party size, active play, queue policy, capacidad, ranked/casual y restricciones.
5. Si el grupo es valido, la modalidad crea admissions para los miembros aceptados.
6. El transporte de modalidad mueve a los jugadores al arena server.
7. El arena server consume admissions y mete a la party en waiting room, match o spectator segun corresponda.

Regla importante:

```text
Una party entra completa o no entra.
```

Si un miembro no puede entrar, la accion completa debe rechazarse o manejarse con una policy explicita. No debe partirse la party accidentalmente.

## Como La Modalidad Sabe Si El Jugador Esta En Party

Las modalidades no deben hablar con estructuras internas del plugin Velocity de PartySystem.

PartySystem debe publicar snapshots runtime en Redis. Las modalidades consumen esos snapshots mediante un adapter/contrato read-only.

Flujo:

1. El jugador inicia una accion de modalidad.
2. La modalidad consulta un adapter read-only de parties.
3. El adapter lee `party-player:<playerId>`.
4. Si no existe, el jugador se trata como solo-player.
5. Si existe, obtiene `partyId` y lee `party:<partyId>`.
6. Congela ese snapshot para la accion actual.
7. Valida la accion usando esa vista estable.

El snapshot congelado evita que cambios posteriores de party alteren admissions ya creadas.

## Contrato Redis Que Debe Exponer PartySystem

PartySystem debe publicar una vista pequena, estable y confiable. No debe exponer todo su modelo interno.

Keys conceptuales:

| Key | Contenido | Uso |
| --- | --- | --- |
| `party-player:<playerId>` | `partyId` actual del jugador | Resolver rapido si un jugador pertenece a una party. |
| `party:<partyId>` | snapshot completo de la party | Leer leader, miembros y estado transferible. |
| `party-version:<partyId>` opcional | version o timestamp | Detectar cambios o races si hace falta. |

Los nombres finales pueden usar prefijo propio del ecosistema HERA. Lo importante es mantener el contrato estable.

Snapshot minimo:

```json
{
  "partyId": "party_abc123",
  "leaderId": "uuid-stephano",
  "members": [
    {
      "playerId": "uuid-stephano",
      "role": "LEADER",
      "state": "ONLINE",
      "transferable": true,
      "serverId": "bedwars-lobby-01"
    },
    {
      "playerId": "uuid-cristian",
      "role": "MEMBER",
      "state": "ONLINE",
      "transferable": true,
      "serverId": "global-lobby-01"
    }
  ],
  "createdAt": "2026-07-05T00:00:00Z",
  "updatedAt": "2026-07-05T00:00:10Z",
  "version": 42
}
```

Campos obligatorios:

- `partyId`: identificador estable de la party.
- `leaderId`: leader actual.
- `members`: lista completa de miembros efectivos.
- `playerId`: UUID del miembro.
- `role`: rol dentro de la party.
- `state`: estado runtime del miembro.
- `transferable`: si puede moverse ahora.
- `serverId`: servidor actual si se conoce.
- `updatedAt` o `version`: frescura del snapshot.

Estados recomendados:

| Estado | Significado |
| --- | --- |
| `ONLINE` | Puede participar si `transferable=true`. |
| `OFFLINE` | No debe entrar en una queue normal. |
| `CONNECTING` | Inicialmente rechazar para evitar races. |
| `DISCONNECTED_RECENTLY` | No admission normal; puede servir para reconnect futuro. |

## TTL, Limpieza Y Consistencia

PartySystem debe evitar datos fantasma.

Reglas:

- borrar o expirar `party-player:<playerId>` cuando el jugador sale de la party;
- borrar o expirar `party:<partyId>` cuando la party se disuelve;
- renovar TTL o actualizar snapshot cuando cambian miembros, leader, estado transferible o server actual;
- si `party-player:<playerId>` apunta a una party inexistente, el snapshot esta stale;
- si `party:<partyId>` contiene un miembro cuyo indice apunta a otra party, el snapshot esta inconsistente;
- ante inconsistencia, la modalidad debe rechazar la accion segura en vez de partir la party.

Para ranked o flujos competitivos, la regla debe ser siempre fail-safe: rechazar si el snapshot no es confiable.

## Aceptar Party Mientras El Leader Esta En Partida

Aceptar una invitacion no debe mover automaticamente al nuevo miembro a una partida.

Si el producto quiere una experiencia de follow spectator, debe pasar por policy de modalidad.

Ejemplo:

```text
Stephano esta en una partida BedWars 1v1.
Cristian acepta invitacion de party.
```

Flujo seguro:

1. PartySystem actualiza la party y publica snapshot.
2. PartySystem detecta que el leader esta en un destino no followable de gameplay.
3. PartySystem no mueve automaticamente a Cristian.
4. Si existe una policy de follow-spectator, PartySystem puede emitir una intencion social.
5. La modalidad valida si Cristian puede spectatear ese match.
6. Si se permite, la modalidad crea admission de spectator.
7. El transporte de modalidad mueve a Cristian al arena server.

Si el match es ranked o spectator externo esta bloqueado, Cristian se queda donde esta.

## Party Policies En Modalidades

Cada modalidad o queue debe declarar como acepta parties.

Politicas base:

| Politica | Uso | Regla |
| --- | --- | --- |
| `SOLO_ONLY` | 1v1 estricto o colas individuales | Rechaza parties con mas de un miembro. |
| `SAME_TEAM` | Duos, squads, ranked teams | La party debe entrar junta al mismo equipo. |
| `ALLOW_SPLIT` | Casual grande | La party puede dividirse entre equipos del mismo match. |

Reglas:

- ranked no debe permitir split por defecto;
- party size no puede exceder capacidad valida de la queue;
- same-team no puede exceder `playersPerTeam`;
- allow-split puede exceder `playersPerTeam` solo si no excede `maxPlayers`;
- una party no debe terminar en matches distintos;
- no debe haber rebalancing silencioso que rompa la policy aceptada.

## Edge Cases Obligatorios

| Caso | Resultado correcto |
| --- | --- |
| Leader cambia a lobby de modalidad followable | PartySystem mueve miembros transferibles segun policy. |
| Leader cambia a arena server | PartySystem no hace follow. |
| Movimiento tiene admission de modalidad | PartySystem no hace follow. |
| Miembro esta offline | No se incluye en entrada normal a queue. |
| Miembro no es transferible | Rechazar accion de gameplay o aplicar policy explicita. |
| Snapshot stale o inconsistente | Rechazar de forma segura. |
| Doble click del leader | Idempotencia por party/destino/accion. |
| Party cambia despues del click | La accion usa el snapshot congelado. |
| Party se disuelve en waiting room | La modalidad aplica policy de room; PartySystem no expulsa por su cuenta. |
| Fallback o kick tecnico | PartySystem no debe iniciar follow automatico. |
| Spectator ranked | Rechazado salvo bypass controlado y auditable. |

## Entregables Del Equipo PartySystem

El equipo PartySystem debe entregar:

- plugin Velocity principal;
- lifecycle completo de party: invite, accept, leave, kick, disband, leader change;
- deteccion de server actual de cada miembro;
- party-follow hacia destinos followables;
- configuracion/registry de destinos followables;
- proteccion anti-loop para movimientos causados por PartySystem;
- snapshots Redis por jugador y por party;
- TTL, cleanup y actualizacion de snapshots;
- documentacion del contrato Redis: keys, payload, estados, TTL y reglas de consistencia;
- API o evento de intencion social opcional para casos como follow-spectator, sin ejecutar gameplay por su cuenta;
- logs suficientes para auditar por que un miembro fue o no fue movido.

## No Hacer

- No hacer que el lobby global lea Redis de party.
- No duplicar reglas de party-follow fuera de PartySystem.
- No seguir automaticamente al leader hacia arena servers.
- No mover jugadores a waiting rooms, matches, reconnect o spectator sin admission de modalidad.
- No decidir matchmaking ni equipos desde PartySystem.
- No partir parties entre matches distintos.
- No confiar solo en plugin messaging para movimientos criticos.
- No dejar snapshots sin TTL o cleanup.

## Resumen Final

PartySystem integra la red social de parties con las modalidades mediante dos responsabilidades claras:

1. Para navegacion social, observa movimientos del leader hacia destinos followables y mueve miembros transferibles.
2. Para gameplay, publica snapshots en Redis para que la modalidad valide y mueva al grupo con sus propias reglas.

La frontera final es simple:

```text
PartySystem mueve followers hacia lobbies followables.
Las modalidades mueven jugadores hacia gameplay validado.
Redis es el puente de informacion entre ambos.
```
