![Ender Link](assets/banner.png)

# Ender Link

Open your ender chest anywhere on Folia. The plugin manages commands, permissions, capacity, and first-login hints. No other plugin is required.

[Watch the Ender Link demo on YouTube](https://youtu.be/auczq3Sn7IQ).

Use `/enderlink` or the inventory button provided by [Ender Link Client](https://github.com/PluginsKers/ender-link-client). Neither requires carrying an ender chest item.

## Installation

Requires Folia 26.2 and Java 25. Place the plugin jar in the server's `plugins` folder and restart.

## Commands and permissions

| Command | Permission | Default |
| --- | --- | --- |
| `/enderlink` | `enderlink.open` | Everyone |
| `/enderlink reload` | `enderlink.reload` | Operators, console, and RCON |
| `/enderlink size <player>` | `enderlink.size.query` | Operators |
| `/enderlink size <player> <27\|36\|45\|54>` | `enderlink.size` | Console and RCON; players need an explicit grant |

The target player must be online. `enderlink.size` includes query permission.

First-login and item-interaction hints include a clickable `[Open]` button. Hovering shows `/enderlink`. Sneaking while right-clicking a block still places the held ender chest.

Messages follow the player's current client language. English (`en_us`) and Simplified Chinese (`zh_cn`) are included. Other languages, the console, and RCON use English.

Startup creates `plugins/enderlink/lang/en_us.properties` and `zh_cn.properties` without overwriting existing files. Edit them as UTF-8, keep placeholders such as `{0}` and `{1}`, then run `/enderlink reload`. Missing keys use bundled messages; missing files are recreated. If reading or validating either file fails, all current messages stay active. Invalid files at startup leave bundled messages active. Reload only updates language files; replacing the plugin jar still requires a server restart.

## Storage

Items remain in vanilla `EnderItems`. Capacity is stored in `enderlink:ender_chest_size`. The legacy `conflux:ender_chest_size` key is migrated and removed on login without requiring the old plugin. No database or inventory copy is created.

Capacity changes are rejected while the chest is open or if shrinking would discard items. Failed saves restore the previous capacity. The server shuts down if storage integrity cannot be confirmed.

Opening a chest fires the native Bukkit `InventoryOpenEvent`, allowing other plugins to restrict access.

Before removing the plugin, empty all extra slots and restore every expanded chest to 27 slots. Migrated data cannot be downgraded directly to versions that only recognize the legacy capacity key. Hot unloading is not supported.

## Client protocol

`enderlink:open_v1` accepts an empty payload. The server opens only the sender's chest after checking connection, life state, game mode, permission, menu, and cursor.

The client uses the registered server channel; no additional handshake or capability cache is needed.

## Building

```sh
./gradlew build nativeTestJar
```

Use `gradlew.bat` on Windows. The version comes from `gradle.properties` and can be overridden with `-Pversion`. Jars are written to `build/libs`. See [TESTING.md](TESTING.md) for native server tests.

## License

Copyright (C) 2026 PluginsKers.

Project code is licensed under [AGPL-3.0-only](LICENSE), with a Minecraft server linking permission. See [NOTICE](NOTICE) for that permission and third-party notices.

The logo and banner use vanilla Minecraft artwork by Mojang Studios and are not covered by this license.
