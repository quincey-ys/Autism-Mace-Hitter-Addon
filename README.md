# Mace Hitter (AUTISM Client addon)

A KillAura-style module built around mace smash attacks.

## Module: "Mace Hitter"

Locks the closest target in reach, faces it, swaps to your mace and slams:

- **No cooldown waiting** - every tick the target is in reach and the
  conditions pass, it swings.
- **Smash setup** - holds jump automatically and attacks while falling so
  every hit is a mace smash (bonus damage, no fall damage on you).
- **Shield stun slam** - if the target raises a shield it is hit instantly,
  even mid-jump: mace smashes disable shields, so blockers get broken first.
- KillAura mechanics: nearest-target selection, rotations, LOS check.

### Settings

| Setting | Default | What it does |
|---|---|---|
| Range | 3.2 | Max distance to acquire/attack |
| Delay (ticks) | 0 | Ticks between swings (0 = every tick) |
| Rotate | on | Snap your view to the target each tick |
| Auto Swap Mace | on | Finds a mace in the hotbar and equips it |
| Auto Jump | on | Bounces you so smashes keep landing |
| Smash Only While Falling | off | Only attack mid-fall (max mace damage) |
| Stun Shield Blockers | on | Always hit shield blockers instantly |
| Require Mace In Hand | off | Do nothing if no mace is available |
| Ignore Walls | off | Skip the line-of-sight check |
| Target Mode | Players | Players only, or every living entity |
| Ignore Teammates | on | Skips players allied to you |

### Macro action: "Slam Now"

Appears in the macro editor. Forces one slam attempt on the current target
immediately, regardless of module state.

## Build

```
cd path/to/autism-client
./gradlew publishToMavenLocal --no-daemon   # one time

cd ../autism-mace-hitter
./gradlew build --no-daemon
```

Jar lands in `build/libs/` - drop it in your `mods` folder.

## Notes

- The module interacts with the world through vanilla client methods only;
  no mixins or packets are injected.
- Method names follow the official (Mojang) mappings shipped with the
  client's Loom setup. If a name changed in your exact build, the compiler
  will point at the line - it is a one-word fix.
