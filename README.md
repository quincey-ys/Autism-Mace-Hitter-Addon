# QYS AUTISM Addon

A client-side addon for AUTISM Client 5.0 on Minecraft 26.2 / Fabric.

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
| Auto Equip Mace | On | Selects a mace from the hotbar |
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
| Target Mode | Players | Players or all living entities |
| Ignore Teammates | On | Skips allied players |

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
