# Cobblemon Extended Battle UI

A client-side Fabric mod that adds helpful information displays to Cobblemon battles, as well as overhauling the default Battle Log.

## What does it do?

During Cobblemon battles, this mod shows you information that's normally hard to track:

![Full Showcase](https://cdn.modrinth.com/data/cached_images/b45609672a6332699d206939f4d3c4e98dfeab86.png)

### Battle Info Panel

A panel on your screen that displays:

- **Weather & Terrain** - Shows active weather (rain, sun, sandstorm, etc.) and terrain effects with remaining turn counters
- **Field Effects** - Trick Room, Gravity, and other field-wide conditions
- **Your Side's Effects** - Screens (Reflect, Light Screen), hazards on your side, Tailwind, etc.
- **Enemy's Effects** - Same as above, but for the opponent
- **Stat Changes** - Shows every Pokemon's stat boosts and drops with easy-to-read arrows
- **Volatile Effects** - Taunt, Encore, Perish Song, Leech Seed, and other per-Pokemon effects

The panel shows turn ranges like "5-8" when we don't know if the opponent has items that extend duration (like Light Clay for screens or weather rocks).

![Battle Info Panel](https://cdn.modrinth.com/data/cached_images/13a0c887394fefc67ae8bc0c02cff379ee14c3fe_0.webp)

This panel is also fully resizable, moveable, and collapsible if you only want to see a quick glance of relevant information!

![Collapsed Panel](https://cdn.modrinth.com/data/cached_images/4c33d13050ade1bfd72ca63aa32103761bd01ce8.png)

### Battle Log

We have also implemented a custom battle log that replaces Cobblemon's default log:

- **Damage Percentages** - Shows how much damage each attack dealt (e.g., "→ 35% to Clefable")
- **Healing Tracking** - Healing shown in green (e.g., "→ +15% to Charizard")
- **Color-Coded Messages** - Different colors for moves, HP changes, effects, and field conditions
- **Turn Separators** - Clear visual separation between turns to improve readability
- **Auto-Scroll** - Follows new messages automatically, stops when you scroll up manually

The battle log uses Cobblemon's native textures and is fully resizable, moveable, and has adjustable font size.

![Battle Log](https://cdn.modrinth.com/data/cached_images/ae64946a56c0acbefa825fda45b51fe8f9e0cf02.png)

### Team Indicators

Pokemon model indicators below each team's health bars showing:

- **Visual Models** - Each Pokemon displayed as a mini 3D model
- **Hover Tooltips** - Hover over any Pokemon to see detailed information
- **Your Pokemon** - Shows all moves and held items
- **Opponent Pokemon** - Shows only revealed moves, items, stat changes, and effects
- **Status Tracking** - Status conditions shown via color tinting on models
- **KO Tracking** - Fainted Pokemon appear grayed out

![Team Indicators](https://cdn.modrinth.com/data/cached_images/5209e6c6d2840da41a911607ddf425533e2783eb.png)

### What this mod does NOT do

This mod does not give you any information that you could not otherwise have obtained. Opposition team size, team members, stats, moves, etc... are not and never will be in scope for this mod. This mod is intended to only give any information that you could get via common knowledge, note taking, or reading the match log in a quick and easy way as a QOL upgrade.

## Controls

### Battle Info Panel & Battle Log
- **Drag the header** - Move the panel anywhere on screen
- **Ctrl + Scroll or [ and ]** - Adjust font size (while hovering over the panel)
- **Drag sides or corners** - Resize panel

### Battle Info
- **V or Click header** - Show/hide the detailed panel

### Battle Log
- **Click bottom-right arrow** - Expand/collapse the log

### Team Indicators
- **Hover** - Show detailed tooltip with moves, items, stats, and effects
- **Ctrl + Scroll or [ and ]** - Adjust tooltip font size (while hovering)

You can rebind keys in the Minecraft keybind settings menu.

Your preferences for panel/log size, position, and font sizes are saved automatically.

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16.0+
- Fabric API
- Fabric Language Kotlin
- Cobblemon 1.7.0+

## Notes

- This is a **client-side only** mod - it works in singleplayer and on servers without the server needing it
- The opponent's full team isn't shown until they send out each Pokemon. This is intended.
- Weather/terrain/screen durations show ranges because we can't know if the opponent has duration-extending items
- When spectating, some information - moves, items, stat changes - are only shown when revealed after you started 
watching

## License

MIT
