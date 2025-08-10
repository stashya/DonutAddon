# DonutAddon

A powerful addon for Meteor Client featuring advanced mining automation and enhanced ESP modules.

## Features

- **AI StashFinder**: Automated base mining below Y=-30 with B* Intelligent Pathfinding
- **Advanced ESP Modules**:
    - Cluster Finder
    - Deepslate ESP (2 variants)
    - Dripstone ESP
    - Stone ESP
    - Hole/Tunnel/Stairs ESP
    - Covered Hole ESP
    - Entity ESP (Pillagers, Wandering Traders)
- **Automation Tools**:
    - Auto Spawner Breaker (Baritone)
    - Auto Rocket
    - Auto RTP
- **Custom HUD**: DonutClient watermark

## Requirements

- Minecraft 1.21.4 (or your version)
- Fabric Loader
- Meteor Client
- Baritone (for AI StashFinder)

## Installation

1. Download the latest release from the [Releases](https://github.com/stashya/Donutaddon/) page
2. Place the `.jar` file in your `.minecraft/mods` folder
3. Ensure you have Meteor Client and Baritone installed

## Building from Source

```bash
git clone https://github.com/stashya/DonutAddon.git
cd DonutAddon
./gradlew build
