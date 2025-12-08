# Cobblemon Extended Battle UI

A client-side Fabric mod that adds helpful information displays to Cobblemon battles.

## What does it do?

During Cobblemon battles, this mod shows you information that's normally hard to track:

### Battle Info Panel

A panel on your screen that displays:

- **Weather & Terrain** - Shows active weather (rain, sun, sandstorm, etc.) and terrain effects with remaining turn counters
- **Field Effects** - Trick Room, Gravity, and other field-wide conditions
- **Your Side's Effects** - Screens (Reflect, Light Screen), hazards on your side, Tailwind, etc.
- **Enemy's Effects** - Same as above, but for the opponent
- **Stat Changes** - Shows every Pokemon's stat boosts and drops with easy-to-read arrows

The panel shows turn ranges like "5-8" when we don't know if the opponent has items that extend duration (like Light Clay for screens or weather rocks).

This panel is also fully resizable, moveable, and collapsible if you only want to see a quick glance of relevant information!

### Team Pokeballs

Small pokeball indicators below each team's health bars showing:
- How many Pokemon each side has (opponent's team is revealed as they send them out)
- Which Pokemon have status conditions (colored pokeballs)
- Which Pokemon have fainted (gray pokeballs)

### What this mod does NOT do

This mod does not give you any information that you could not otherwise have obtained. Opposition team size, team members, stats, moves, etc... are not and will never will be in the scope for this mod. This mod is intended to only give any information that you could get via common knowledge, note taking, or reading the match log in a quick and easy way as a QOL upgrade.

## Controls

- **V or Click header** - Show/hide the detailed panel
- **Drag the header** - Move the panel anywhere on screen
- **CTRL + Scroll wheel or [ and ]** - Scroll up or press ] to increase font size, scroll down or press [ to decrease it
- **Drag sides or corners** - Resize panel

You can also rebind these keys to anything you want within the keybind settings menu.

Your preferences for panel size and state are saved automatically.

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

## License

MIT
