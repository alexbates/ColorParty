# Color Party

**Color Party** is a fast-paced multiplayer Minecraft minigame (Spigot plugin) inspired by Hypixel's *Pixel Party* and HiveMC's *Block Party*. Players must quickly move to the correct color tile before the floor disappears beneath them. The game consists of 25 rounds that become progressively shorter and more intense.

At the start of each round, a terracotta block is placed in each player's inventory, indicating the safe color. Players have up to 5 seconds to relocate to that color. After the timer ends, all other tiles vanish—eliminating anyone standing on the wrong block.

## Features

- **Villager NPC spawn point** at `59.5, 66, -392.5` in the Overworld. Right-clicking the NPC teleports players to *ColorPartyWorld*.
- In *ColorPartyWorld*, any player can start the game by right-clicking:
  - **Emerald** for Normal Mode  
  - **Amethyst Shard** for Crazy Mode
- Game continues until all players fall except one.
- After the game ends, players must click the Exit bed and rejoin via the NPC to reset the map.
- Supports Java version 21 (tested with OpenJDK 21.0.3).
- Supports Spigot version 1.21 (tested with 1.21.4 and 1.21.5).

## Game Modes

### 🎯 Normal Mode

- 20 unique dance floor designs selected randomly each round. Design your own using [Color Party Dance Floor Designer](https://github.com/alexbates/ColorPartyDesigner).
- 60% chance per round that **3 beacons** spawn on the dance floor, each granting a random powerup.

### 🔥 Crazy Mode

Includes everything in Normal Mode, with one additional effect per round:

1. **Snow Storm** – Snow gradually covers the dance floor, making it hard to see.
2. **Rolling Colors** – Floor shifts continuously, forcing players to adapt on the move.
3. **Color Bombs** – Withers spawn and shoot exploding projectiles that change block colors.
4. **Jockey Chaos** – Chicken jockeys spawn, paint the floor behind them, and knock players back.

## 🎁 Powerups

Triggered by clicking a spawned beacon:

1. **Leap Axe** – A one-time-use axe that launches the player forward.
2. **Color Cow** – A cow that explodes after a delay, changing nearby blocks to the correct color.
3. **Jump Potion** – One-time-use potion with enhanced jump boost.
4. **Speed Potion** – One-time-use potion with increased speed.
5. **Color Trail** – Player leaves a trail of color. Standing still becomes deadly.
6. **Teleport Clock** – Teleports player instantly to the correct color block.
7. **Random Teleport** – Teleports player to a random tile.
8. **Hunger Curse** – Player is starved for the round, disabling sprint.
9. **Glass Magic Carpet** – A floating glass platform prevents the player from falling for the rest of the round.

## Screenshots

![Screenshot 1](https://tamariapp.com/images/colorparty/1.png)

![Screenshot 2](https://tamariapp.com/images/colorparty/2.png)

![Screenshot 3](https://tamariapp.com/images/colorparty/3.png)

![Screenshot 4](https://tamariapp.com/images/colorparty/4.png)

![Screenshot 5](https://tamariapp.com/images/colorparty/5.png)

![Screenshot 6](https://tamariapp.com/images/colorparty/6.png)

![Screenshot 7](https://tamariapp.com/images/colorparty/7.png)

![Screenshot 8](https://tamariapp.com/images/colorparty/8.png)

## Installation

Download **ColorPartyPlugin.jar** from the [Releases](https://github.com/alexbates/ColorParty/releases) section and place it in the `plugins` directory of your Minecraft Spigot server.

Please remember that you may experience difficulty if attempting to use versions other than OpenJDK 21 and Spigot 1.21.

## Build from Source

Building from source is recommended if you want to customize aspects of the game—such as the NPC spawn location or the return coordinates used when players right-click the *Exit* bed.

### Steps to Build

1. **Create a new IntelliJ IDEA project** named `ColorPartyPlugin`.

2. **Project Structure**:
   - Inside your project, create an `src` directory.
   - Within `src`, create two packages:
     - `com.example.colorpartyplugin` (copy all Java files here)
     - `resources` (copy the following files here: `plugin.yml`, all `.nbs` files, and all `.json` files)

3. **Configure Project SDK and Libraries**:
   - Go to **File > Project Structure**.
   - Under **Project**, set the SDK to **21 (Oracle OpenJDK)**.
   - Under **Libraries**, add the following dependencies:
     - `gson-2.9.1`
     - `NoteBlockAPI-1.6.3`
     - `spigot-api-1.21.4-R0.1-SNAPSHOT`

4. **Artifacts Configuration**:
   - Under **Artifacts**, make sure all `.json`, `.nbs`, and other resources are included at the root level of `ColorPartyPlugin.jar`.
   - This may require manually adding each resource file.

5. **Modules Setup**:
   - Under **Modules**, ensure all libraries are selected for export.
   - Set the scope for each library to **Provided**.

6. **Build**:
   - After applying all changes, use **Build > Build Artifacts > Build** to generate `ColorPartyPlugin.jar`.

You can now place your newly built `.jar` file into your server’s `plugins` folder and restart the server to load your custom version of Color Party.
