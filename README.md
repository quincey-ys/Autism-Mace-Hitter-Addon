# QYS AUTISM Addon

A client-side addon for **AUTISM Client 5.0-26.2 (API v3)** on Minecraft 26.2 / Fabric. The release is version-locked to the Client 5.0 API line; it is not a Client 5.1 / API v4 build.

The Fabric mod ID remains `autism-mace-hitter` intentionally so AUTISM Client can keep the addon identity and saved settings. The displayed addon name is **QYS Addon**.

## Module: Mace

The former Mace Hitter module is now named **Mace**.

- **Stun Slam (Axe -> Mace):** attacks a raised shield with an axe, then waits for a valid falling mace smash.
- **Sword -> Breach Mace Swap:** when a sword is held and an armored player is in ordinary melee range, selects a Breach-enchanted mace for the attack while retaining the sword's pre-swap attributes for the same-tick hit. This depends on server-side attribute-swap behavior.
- **Mace Attribute Swap:** optionally selects the mace for a falling smash while retaining the source weapon's attributes for the same-tick hit.
- **Spear Charge -> Mace Double Pop:** this path is active only while the player is actively charging a main-hand spear. It uses the spear's native `ATTACK_RANGE` component to find a target that can actually be reached by the spear. Once a spear hit is confirmed by the target's hurt-time/health update, it immediately selects the mace, attacks once, and restores the spear in the same tick. There is no extra fall-distance gate, hurt-reset wait, or delayed follow-up timer.
- **Slot restoration:** optionally restores and synchronizes the original hotbar slot after a combo.

The spear double-pop's extended range is gated by the held spear's actual item-defined attack range; it is not active while the player is idle, holding a different item, or merely carrying a spear. The immediate mace follow-up uses a same-tick attribute swap. Servers can still reject or normalize the swap, and the spear hit must be observable by the client before the follow-up can be triggered.

### Mace settings

| Setting | Default | Purpose |
|---|---:|---|
| Normal Range | 3.2 | Ordinary target range |
| Attack Delay | 0 ticks | Delay between ordinary attacks |
| Rotate to Target | On | Faces the current target |
| Auto Equip Mace | On | Equips a mace on the same tick as a ready attack; does not switch early while waiting for falling state or attack cooldown |
| Auto Jump | On | Helps create falling smash windows |
| Only Attack While Falling | Off | Restricts ordinary attacks to falling |
| Stun Slam (Axe -> Mace) | On | Axe shield-disable followed by a falling mace hit |
| Mace Attribute Swap | Off | Same-tick Mace swap for a falling smash using the source weapon's attributes |
| Sword -> Breach Mace Swap | Off | Situational Breach swap against armored players |
| Breach Target Armor | 8 | Minimum target armor value |
| Spear Charge -> Mace Double Pop | Off | Only while charging the main-hand spear; immediate mace hit after confirmed spear contact |
| Minimum Smash Fall | 1.5 | Minimum fall distance for ordinary mace smash swaps |
| Restore Original Slot | On | Restores the original hotbar selection |
| Require Mace Available | Off | Prevents attacks when no mace can be found |
| Ignore Walls | Off | Skips line-of-sight validation |
| Entities | Players only by default | Registry-backed multi-select picker, matching AUTISM KillAura; choose exact entity types such as players, zombies, skeletons, or other mobs |
| Targeting | FOV | Chooses targets by Type, HP, Distance, FOV, Hurt Time, or Age |
| FOV | 180° | Maximum target cone; 360° allows targets in every direction |
| Ignore Teammates | On | Skips allied players |

With **Auto Equip Mace** enabled, the module waits until a valid attack window is open before changing the selected slot. It respects **Only Attack While Falling** and the vanilla attack cooldown, so the mace is not equipped early while waiting for a smash. Stun Slam also keeps the axe selected during its setup and switches to the mace only on the actual falling-smash tick.

The **Entities** picker is a whitelist: only selected entity types can be targeted. Its default is `minecraft:player`. The selected entity filter and FOV check are shared by ordinary Mace targeting, Stun Slam acquisition, and the spear-charge target scan. The spear scan still requires the entity to be inside the held spear's native attack-range ray.


## Module: Combat

**Combat** is independent from **Mace** and **Hitflick**. It contains four optional combat utilities plus tracked blocking. A shared per-client-tick attack guard coordinates attack actions with Mace so both modules do not deliberately issue two attacks in the same tick.

- **Tracked Blocking:** tracks each nearby entity's blocking state by entity ID and UUID, records when a block begins/ends, and expires stale tracking records.
- **Shield Breaker:** on a newly observed blocking window, selects an axe, attacks once, and returns to the configured weapon. It has a cooldown and a configurable tracked disable window. This is an attack-based shield disable; actual disable duration depends on server rules and axe cooldown.
- **Smart Smash:** checks that the player is falling, the configured fall distance is reached, a target is in range and visible, attack cooldown is ready, and a mace is available. It performs at most one smart-smash attack per fall. Against sufficiently armored players it prefers a Breach mace when one is available.
- **Spear Counter:** detects nearby players actively using a spear while facing and moving toward the local player. It can briefly backstep/strafe and optionally switch to a sword or axe, then restores the prior hotbar slot if the player has not manually changed it. Detection is heuristic and depends on entity movement updates.
- **Weapon Optimizer:** selects a hotbar weapon based on the target and current situation: axe for a blocking target, mace (preferably Breach against high armor) during a valid smash window, spear when its item-defined range includes the target at longer distance, otherwise sword/axe/mace fallback. Switching is cooldown-limited.

### Combat settings

| Setting | Default | Purpose |
|---|---:|---|
| Entities | Players | Registry-backed entity whitelist |
| Track Blocking | On | Track nearby blocking state |
| Shield Breaker | Off | Axe attack on a new blocking window |
| Shield Breaker Cooldown | 500 ms | Prevent repeated shield-break attempts |
| Shield Disabled Window | 100 ticks | Local tracking window after axe hit; not a guarantee of server shield-disable time |
| Shield Breaker Reach | 3.2 | Maximum distance for shield reaction |
| Return Weapon | Previous | Previous, Sword, Mace, Spear, or Best Weapon |
| Smart Smash | Off | Context-aware falling mace attack |
| Smart Smash Minimum Fall | 1.5 | Minimum fall distance |
| Smart Smash Target Range | 3.2 | Maximum target distance |
| Restore Slot After Smash | Off | Restore the pre-smash hotbar slot |
| Spear Counter | Off | Detect an aimed, approaching spear user |
| Spear Counter Range | 8.0 | Threat scan range |
| Attacker Facing Angle | 55° | Maximum angle between spear user's view and local player |
| Counter Action | Backstep | Backstep, strafe, switch sword/axe, or combine switch + backstep |
| Counter Duration | 4 ticks | Duration of movement/switch assist |
| Counter Cooldown | 600 ms | Minimum time between counters |
| Weapon Optimizer | Off | Automatically choose a weapon |
| High Armor Threshold | 12 | Armor value for Breach/axe preference |
| Spear Selection Distance | 3.2 | Distance beyond which spear selection is considered |
| Weapon Switch Cooldown | 250 ms | Prevents rapid hotbar switching |
| Target Scan Range | 8.0 | Nearby target scan radius |
| Target Priority | FOV | Type, HP, Distance, FOV, Hurt Time, or Age |
| FOV | 180° | Target cone; 360° permits all directions |
| Ignore Walls | Off | Skip line-of-sight checks |
| Ignore Teammates | On | Skip allied players |

Shield and spear threat detection are client-side observations. Server latency, entity update timing, shield-disable mechanics, and anti-cheat can affect whether an action succeeds. Spear Counter is a reactive movement assist, not a guaranteed dodge or a prediction of the server's exact attack timing.

## Module: Hitflick

**Hitflick** is a separate dropdown module. It sends a brief server-side yaw change immediately before a local attack and restores the original yaw after the attack. It does not change the visible camera rotation.

| Setting | Default | Purpose |
|---|---:|---|
| Direction | Alternate | Left, Right, or alternating yaw flick |
| Flick Angle | 30 degrees | Temporary yaw offset |
| Require Sprint | Off | Only flick while sprinting |
| Cooldown | 100 ms | Minimum interval between flicks |
| Chance | 100% | Random activation chance per eligible attack |

Hitflick changes attack-facing direction only; it does not guarantee a particular knockback result. Server movement validation, latency, and anti-cheat behavior can affect the outcome.

## Macro action

**Slam Now** remains available in the macro editor and attacks the module's current target.

## Browser-only build

The GitHub Actions workflow builds the upstream AUTISM Client API into the runner's Maven Local repository, then builds this addon. No local Codespace, Java installation, or Gradle wrapper is required.

1. Open the repository's **Actions** tab.
2. Select **Build QYS Addon**.
3. Run the workflow with **Run workflow**, or let a push trigger it.
4. Open the completed run and download the **qys-autism-addon** artifact.
5. Put the resulting JAR in your Minecraft `mods` folder alongside AUTISM Client and Fabric API.

The workflow also runs automatically for pushes and pull requests.
