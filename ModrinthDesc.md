# OpenRail
**An automatic rail routing and switching plugin designed for PaperMC servers.**

Automatically builds a rail network in memory as you place rails in-game. Define stations and junctions to set up destinations and switching points across the network. Uses a bidirectional search across the network to find your optimal route to any station.

## Features
### Stations
Define a station by placing a sign labelled ```[station]``` on the first line followed by the name of the station on the second. Station signs must be placed one block above a rail to be considered a valid station.

![Example station](https://cdn.modrinth.com/data/cached_images/2997a7d8bda8f5e4ce9ae5c1aff813a113bc1d68.png)

### Junctions
Junctions are created in the same way, placing a sign lablled ```[junction]``` above a rail. Junctions will automatically switch the direction of the rail below and all adjacent connected rails.

![Example Junction](https://cdn.modrinth.com/data/cached_images/72b7342c6acc7e2b0bb69541c2681c8852e504db_0.webp)

### Copper Rails
Copper blocks, grates, or cut copper placed 1-2 blocks below a rail will provide a speed boost toggleable with ```/copper rails true|false```. By default normal copper provides a 2x top speed boost, exposed provides 1.7x, weathered provides 1.3x, and oxidized provides 1.1x. Waxxing copper blocks maintains the speed boost. This can be modified in the config.yml or with the command ```/coppermultiplier <unoxidized|exposed|weathered|oxidized> <multiplier>.

## How to use
The rail network graph is automatically built as you place rails in-game. Creating a station or junction automatically adds nodes to the graph. When rail is broken that segment is disconnected from the network so long as it has no other connection to the network.

After setting up a station simply use ```/dest <station_name>``` and be routed automatically to your destination!

Sending a redstone signal to any rail in a junction will cause that specific rail to no longer change direction. This allows you to make true one-way sides for junctions.

Holding left or right while riding in a minecart lets you manually control the direction a junction will route.

### Other Commands
```/dest```: Clears destination.

```/stations [search] [page]```: Lists stations ordered by proximity with distance and direction.

```/railmap``` & ```/railmap info```: Shows the line you're standing near, its two ends, whether it's one way, its length, and the estimated travel time.

```/railmap retrace```: Rebuilds the whole network. Useful for when rails are placed by other tools like WorldEdit or if copying an existing world on to the server.




## Supported Plugins
### BlueMap
When BlueMap is installed alongside OpenRail it automatically display station and junction waypoints (by default only stations are shown) as well as lines showing the path between nodes.

![BlueMap Top-down Network View](https://cdn.modrinth.com/data/cached_images/a4ce91bbd3c788afe238a7d35bcea0406cff478f.png)
![BlueMap Network View](https://cdn.modrinth.com/data/cached_images/80ce084c531828b5d71eac117c827003affa2e83.png)