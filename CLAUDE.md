# Claude Code Project Guide

## Project Overview

Fabric mod for Minecraft 1.21.1 that adds an enhanced battle information panel to Cobblemon. Displays weather, terrain, field conditions, side conditions (screens, hazards), and stat stages during battles.

**Stack**: Kotlin + Java (mixins), Fabric, Minecraft 1.21.1, Cobblemon 1.7.1

## Build Commands

```bash
./gradlew build          # Build the mod (output in build/libs/)
./gradlew runClient      # Run Minecraft with the mod loaded
```

## Project Structure

```
src/main/
├── kotlin/com/cobblemonextendedbattleui/
│   ├── CobblemonExtendedBattleUI.kt      # Server-side mod entry (minimal)
│   ├── CobblemonExtendedBattleUIClient.kt # Client entry, keybind registration
│   ├── BattleInfoPanel.kt                 # Main UI panel (largest file ~1000 LOC)
│   ├── BattleStateTracker.kt              # Tracks all battle state
│   ├── BattleMessageInterceptor.kt        # Parses Cobblemon battle messages
│   ├── TeamIndicatorUI.kt                 # Pokeball team indicators
│   ├── BattleInfoRenderer.kt              # Render dispatcher
│   └── PanelConfig.kt                     # Persistent config (JSON)
├── java/com/cobblemonextendedbattleui/mixin/
│   ├── BattleMessageHandlerMixin.java     # Intercepts battle messages
│   ├── BattleEndHandlerMixin.java         # Clears state on battle end
│   └── MouseScrollMixin.java              # Intercepts scroll for panel
└── resources/
    ├── fabric.mod.json                    # Mod metadata
    ├── cobblemonextendedbattleui.mixins.json
    ├── cobblemonextendedbattleui.accesswidener
    └── assets/.../lang/en_us.json         # Translations
```

## Key Architecture Decisions

### Keybind System
Minecraft's standard keybinding system (`wasPressed()`) doesn't work during Cobblemon's battle overlay because the battle UI intercepts input events. Solution:

1. Register keybinds normally (so they appear in Minecraft settings)
2. In `handleInput()`, poll the bound key directly with GLFW
3. Use `InputUtil.fromTranslationKey(keybind.boundKeyTranslationKey)` to get the bound key
4. Check key type for mouse button support:
```kotlin
when (key.category) {
    InputUtil.Type.MOUSE -> GLFW.glfwGetMouseButton(handle, key.code)
    else -> GLFW.glfwGetKey(handle, key.code)
}
```

### State Management
- `BattleStateTracker` is the single source of truth for battle state
- Tracks battle ID to clear state when a new battle starts
- Uses `ConcurrentHashMap` for thread safety
- State is cleared via mixin on battle end AND checked on each render frame

### Message Parsing
`BattleMessageInterceptor` parses Cobblemon's translation keys to detect:
- Stat changes: `cobblemon.battle.boost.*`, `cobblemon.battle.unboost.*`
- Weather: `cobblemon.battle.weather.*.start/end`
- Terrain: `cobblemon.battle.fieldstart/end.*terrain`
- Side conditions: `cobblemon.battle.sidestart/end.ally/opponent.*`
- Turns, faints, switches

### Rendering
- Uses Cobblemon's `drawScaledText` for text rendering
- Manual scissor bounds tracking because `drawScaledText` doesn't respect GL scissor
- `drawTextClipped()` checks Y bounds before rendering to prevent overflow
- Height calculations must account for scrollbar width (always assume worst case)

## Common Patterns

### Adding a new tracked condition
1. Add enum value in `BattleStateTracker`
2. Add translation key mapping in `BattleMessageInterceptor`
3. Add rendering in `BattleInfoPanel.renderExpanded()`
4. Update height calculation in `calculateExpandedContentHeight()`

### Config changes
- Add field to `PanelConfig.ConfigData`
- Add property to `PanelConfig` object
- Update `load()` and `save()` methods
- Config file: `config/cobblemonextendedbattleui.json`

## Gotchas

1. **Scissor test doesn't clip Cobblemon's text** - Must manually check bounds before drawing
2. **Height calculation must match rendering** - If scrollbar appears, width changes, stats wrap differently
3. **Battle state persists** - Must clear on new battle (check battleId) not just battle end
4. **Keybinds need GLFW polling** - Standard Minecraft keybind events don't fire during battle
5. **Mixins are Java only** - Mixin classes must be in `src/main/java`

## Testing Checklist

- [ ] Panel toggles with V key (and custom bindings)
- [ ] Panel toggles with mouse buttons (mouse 4/5)
- [ ] Font size adjusts with `[`/`]` and Ctrl+Scroll
- [ ] State clears when starting new battle
- [ ] State clears after save/reload world
- [ ] Stats don't overflow panel when scrollbar appears
- [ ] Resize handles work in all 8 directions
- [ ] Position/size persists after restart
