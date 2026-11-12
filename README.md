# QYS AUTISM Addon

A client-side addon for AUTISM Client 5.0 on Minecraft 26.2 / Fabric.

The Fabric mod ID remains `autism-mace-hitter` intentionally so AUTISM Client can keep the addon identity and saved settings. The displayed addon name is **QYS Addon**.

## Module: Mace

The former Mace Hitter module is now named **Mace**.

- **Stun Slam (Axe -> Mace):** attacks a raised shield with an axe, then waits for a valid falling mace smash.
- **Sword -> Breach Mace Swap:** when a sword is held and an armored player is in ordinary melee range, uses a Breach-enchanted mace for the situational same-tick attribute swap. This depends on server-side attribute-swap behavior.
- **Mace Attribute Swap:** optionally performs a same-tick mace attack during a falling smash.
- **Spear Charge -> Mace Double Pop:** tracks a genuine spear use/charge, detects a confirmed spear hit, switches to the mace, then waits for the target's hurt window to clear and for a legal-range falling smash before attempting the second hit.
- **Slot restoration:** optionally restores and synchronizes the original hotbar slot after a combo.

The spear double-pop does not force mace reach to equal spear reach. The follow-up uses the mace's own legal range; servers can still reject a second hit due to damage immunity, timing, or anti-cheat.

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
| Mace Attribute Swap | Off | Same-tick mace attribute swap during a smash |
| Sword -> Breach Mace Swap | Off | Situational Breach swap against armored players |
| Breach Target Armor | 8 | Minimum target armor value |
| Spear Charge -> Mace Double Pop | Off | Detects a spear hit, then attempts a mace smash follow-up |
| Spear Charge Tracking Range | 4.5 | Maximum range for spear-charge tracking |
| Minimum Smash Fall | 1.5 | Minimum fall distance for smash combos |
| Spear Follow-up Window | 40 ticks | Maximum time allowed for the second hit |
| Wait for Target Hurt Window | On | Waits for target hurt time to clear |
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
