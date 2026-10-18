# Quest port notes (coah-quest-support)

This branch carries coah80's QuestCraft Visor changes on top of QuestCraftPlusPlus/Pojlib `QuestCraft-6.0.0`.

## Bundled mod binaries (`src/main/assets/mods/`)

The installer copies these jars from APK assets into the instance's `mods/` folder, so they are vendored.

| File | Source | License |
|---|---|---|
| `Visor.jar` | Plinth-MC/Visor `coah-quest-support` @ 1232f63, `:visor-fabric:1.20.1:buildAndCollect` -> `Visor-0.6.0-beta+mc1.20.1-fabric.jar` (sha256 3ab61e87df6652bd7b5a0eebacdd22bfd47e98440c17cc850b7f99483f9baa7b) | MPL-2.0 (Visor), AtumVR inside |
| `VisorEssentials.jar` | prebuilt 0.4.0 by PhoenixRa, added by coah | LGPLv3 |
| `BadOptimizations.jar`, `C2ME.jar`, `Chunky.jar`, `MoreCulling.jar`, `Noisium.jar` | prebuilt Modrinth releases, added by coah | see each mod |

To update Visor: build the Visor branch above, then copy the jar over `src/main/assets/mods/Visor.jar` and update the version in `Installer.java`.

## LWJGL / OpenXR

Pojlib's `assets/lwjgl/lwjgl-glfw-classes.jar` is QuestCraft's LWJGL 3.3.3-based core and has no OpenXR classes.
The Visor jar includes `lwjgl-openxr-3.3.6` (and `lwjgl-vulkan-3.3.6`) as Fabric jar-in-jar, which AtumVR uses.
All `org.lwjgl.*` core members referenced by lwjgl-openxr 3.3.6, AtumVR and Visor were checked with `javap` against the bundled core jar and resolve, so no LWJGL core change is needed. Smoke tested on a Quest 3: the OpenXR session reaches READY.

The jar-in-jar `lwjgl-openxr` natives are the Linux glibc build and cannot load on Android, so `JREUtils` sets `-Dorg.lwjgl.openxr.libname` to the Android `libopenxr_loader.so` in the APK's native lib dir.
