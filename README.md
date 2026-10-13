# OpenRail

Automatic minecart routing for **Paper 26.1+** (Java 25). Players type `/dest <station>`, ride, and arrive:
junctions switch themselves along the quickest route. The network is built out from the rails themselves as you
build in-game. For examples see [ModrinthDesc.md](ModrinthDesc.md)

## Building

Requires an internet connection (Gradle downloads Paper API and, if needed, a JDK 25 toolchain).

```
./gradlew build
```

The plugin jar is written to `build/libs/OpenRail-1.0.0.jar`. Drop it into your server's `plugins/` folder.
[BlueMap](https://bluemap.bluecolored.de) is optional.

## Building a network

There are two signs, and only stations have names.

**Stations** go directly above a platform rail:

```
[station]          <- line 1 (case-insensitive)
Mall               <- line 2: the name players type after /dest (one word; use - or _ instead of spaces)
Food court         <- lines 3-4: optional description
```

* The sign goes directly above a platform rail (any kind of rail). That rail *is* the station: a cart only
  arrives (the arrival message and clearing `/dest`) when it reaches that exact block.

**Junctions** go directly above the junction rail, the plain rail where track meets:

```
[junction]         <- line 1
Eastgate           <- line 2: optional label for the map (not a destination)
```

* The junction rail must be a plain rail on the same y-level so that it can curve.
* No redstone is needed. OpenRail sets the rail's shape itself as each cart approaches, so any two sides of a T-junction or
  cross can be joined and junctions work from every direction. Adjacently connected rails are switched too.
* **Redstone overrides OpenRail:** if any rail in a junction is powered that specific rail will not receive block updates from OpenRail.
* Track can split or merge without a sign (for example separate tracks for each direction). Only signed
  junctions are switched, and only stations and junctions are points on the network.

**Connecting it:** As soon as track joins two points (stations or junctions), that stretch is part of
the network and `/dest` can route over it. OpenRail follows the rails out of every station and junction the way a
cart would, and follows them again whenever rails (or the copper under them) are placed, broken, blown up or
pushed by pistons. **Hold left or right** to manually steer through a junction.

## Travelling

| Command | Effect |
|---|---|
| `/dest <station>` | Plan a trip to a station from the nearest station or junction |
| `/dest` | Clear your destination |
| `/stations` | Stations in your world, nearest first, with distance and compass direction (`/stations mall`, `/stations 2`) |

Board a cart and go. `/dest` searches from the station or junction nearest you; once your cart is moving on the
track, the route is searched again from the station or junction it will come to next, so the plan starts from where
you really are. After that each junction is switched from the stored plan;
nothing is searched. Only if your cart reaches a junction
that isn't on the rest of the plan (redstone sent it another way, you got off and on elsewhere, the track changed)
does OpenRail search again, from that junction, never backing out the way you came. When you reach the station
your destination clears ("Arrived at Mall").

If no track leads to your destination, you're told once at the first junction, and you can steer by holding left or right.
With no destination set, you always steer. Steering only works at `[junction]` rails; apart from a junction's
arms, unsigned rails are never switched.

### How routes are chosen

* **Network:** every station and junction is a node; every stretch of track between two of them is an edge.
* **Weights:** each edge is weighted by its time at top speed. OpenRail considers coppered rails when doing this. Copper setting changes (`/copperrails`,
  `/coppermultiplier`) take effect on routes immediately.
* **Search:** a bidirectional search runs forward from the starting junction and backward from the station (from
  all of them, if several share the name), and the two meet in the middle.
* **Compass tie-break:** when two whole routes are about equally quick (within `routing.tie-tolerance-seconds`,
  and never more than 5% apart), the one heading closer to the straight line towards your destination wins. The
  search itself is exact, so near-ties can never add up hop by hop into a longer route.
* **No reversing:** a cart never gets routed back out of the side of a junction it came in on.
* **Any number of hops:** the search runs over every stretch, so a junction only needs to be connected
  to the network somehow; it doesn't need a stretch leading straight to the station.
* **New and removed points:** signing a station or junction on existing track splits the stretches there at once.
  Removing one (breaking its sign or rail, which is logged) joins them back up; put the sign or plain rail back and
  it splits them again.
* **Directions:** a stretch is only used in the directions a cart can actually follow it, so separate tracks for
  each direction stay separate. `/railmap info` says when a line is one way only.

## Track map

* Each way out of a station or junction is followed rail by rail, the way a cart would go, to the next station or
  junction. Junction arms count as the switcher would set them: an arm that can lead on more than one way gives a
  line for each, and track that splits right beside another junction gives a line into it and one past it.
* Only the stretch around a change is followed again (from just before it, rejoining the old line just after it),
  so building or editing a long line costs about the same per block as a short one.
* Track running into an unloaded chunk waits there and carries on when the chunk loads. Changes made while
  OpenRail isn't running, or by tools that don't report block changes (such as WorldEdit), aren't seen until
  something nearby changes again or an operator runs `/railmap retrace`.
* Unsigned splits are followed as their rail is shaped; a rail reached from a side it doesn't join counts as the
  end of the track.
* Lines are simplified to their corners for the map. Stored in `plugins/OpenRail/tracks.yml`. Routing and
  estimates work with or without BlueMap.

Each line has an estimate such as **Est. Time: 0:12-0:24**. The first time assumes every rail is over fresh
(unoxidized) copper; the second assumes no copper. Both assume top speed the whole way. When copper rails are off,
only the no-copper time is shown.

| Command | Who | Effect |
|---|---|---|
| `/railmap` or `/railmap info` | everyone | The line you're standing on: end points, length, Est. Time, and whether it's one way |
| `/railmap retrace` | operators | Follow every line again from the rails (e.g. after editing track with WorldEdit) |

## BlueMap

If BlueMap is installed, OpenRail adds three layers to every map. Markers update live and come back
automatically after `/bluemap reload`.

* **Rail Stations** (shown): name, description, the `/dest` command and coordinates.
* **Rail Lines** (shown): the track between stations and junctions, labelled with its Est. Time.
* **Rail Junctions** (in the layer menu, off by default): each junction and the stations/junctions it connects to.

## Copper speed boost

A copper block, copper grate, or cut copper block **1 or 2 blocks beneath a rail** raises the top speed of minecarts carrying a player:

| Copper under the rail (block or grate, waxed or not) | Default top-speed multiplier |
|---|---|
| Unoxidized | 2.0x |
| Exposed | 1.7x |
| Weathered | 1.3x |
| Oxidized | 1.1x |

Copper only raises the **top speed**; powered rails (or slopes) still do the accelerating, and momentum a cart has
stored above its limit is trimmed when it rolls onto faster copper. On a fully unoxidized line, about 8 powered
rails in a row take a ridden cart from a standstill to 16 blocks/s.

**Note:** at 2x speed carts can overshoot sharp curves and slopes under vanilla physics. Keep the fastest
copper on straight runs.

| Command (operators) | Effect |
|---|---|
| `/copperrails true` / `false` | Turn the copper effect on or off (no argument shows the state) |
| `/coppermultiplier <state> <multiplier>` | e.g. `/coppermultiplier exposed 1.5`; states are `unoxidized`, `exposed`, `weathered`, `oxidized`. Must be above 0 and at most 3.75 (warning above 2.5) |

Both save to `config.yml`.

## Permissions

| Permission | Default | Purpose |
|---|---|---|
| `openrail.dest` | everyone | Use `/dest` |
| `openrail.stations` | everyone | Use `/stations` |
| `openrail.railmap` | everyone | Use `/railmap info` |
| `openrail.station.create` | everyone | Create `[station]` signs |
| `openrail.junction.create` | everyone | Create `[junction]` signs |
| `openrail.admin` | operators | Use `/copperrails`, `/coppermultiplier` and `/railmap retrace` |
