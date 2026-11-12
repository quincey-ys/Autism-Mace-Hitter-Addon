# QYS AUTISM Addon

A client-side addon for AUTISM Client 5.0 on Minecraft 26.2 / Fabric.

The Fabric mod ID remains `autism-mace-hitter` intentionally so AUTISM Client can keep the addon identity and existing module configuration. The displayed addon name is **QYS Addon**.

## Module: Mace

The former Mace Hitter module is now named **Mace**. Its main features are situational and independently configurable:

- **Stun Slam (Axe -> Mace):** hits a shield user with an axe first, then waits for the mace attack cooldown and a falling smash window before hitting with the mace.
- **Spear Reach -> Mace Swap:** when a spear is already held and the target is outside normal range but inside the configured spear range, performs a same-tick swap attack with the mace.
- **Mace Attribute Swap:** optionally performs a same-tick mace attack when a falling smash is ready, using the currently held item as the source attributes where the server permits the technique.
- **Slot restoration:** optionally restores and synchronizes the original hotbar slot after a swap.

The spear and attribute-swap features are off by default. Server support varies; server software and anti-cheat can reject or normalize these interactions.

### Settings

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
| Spear Reach -> Mace Swap | Off | Uses held spear reach for a situational mace swap |
| Spear Reach | 4.5 | Maximum range for spear-assisted target acquisition |
| Minimum Smash Fall | 1.5 | Minimum fall distance for smash combos |
| Restore Original Slot | On | Restores the original hotbar selection |
| Require Mace Available | Off | Prevents attacks when no mace can be found |
| Ignore Walls | Off | Skips line-of-sight validation |
| Target Mode | Players | Players or all living entities |
| Ignore Teammates | On | Skips allied players |

## Macro action

**Slam Now** remains available in the macro editor and attacks the module's current target.

## Browser-only build

The GitHub Actions workflow builds the upstream AUTISM Client API into the runner's Maven Local repository, then builds this addon against it. No local Codespace, Java installation, or Gradle wrapper is required.

1. Open the repository's **Actions** tab.
2. Select **Build QYS Addon**.
3. Run the workflow with **Run workflow**, or let a push trigger it.
4. Open the completed run and download the **qys-autism-addon** artifact.
5. Put the resulting JAR in your Minecraft `mods` folder alongside AUTISM Client and Fabric API.

The workflow also runs automatically for pushes and pull requests.
