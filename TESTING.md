# Testing

`./gradlew build nativeTestJar` runs unit tests, builds the plugin, and compiles the native Folia test plugin.

Unit tests cover player commands, item-interaction hints, event cancellation, capacity rollback, Bukkit permissions, legacy capacity migration, first-login hints, and menu and cursor checks for commands and client requests.

Localization tests cover client language changes, English fallback, the command sender's language, console and RCON messages, button clicks and hover text, and failure messages with capacity rollback.

For native tests, use an empty server directory with Folia 26.2, Java 25 or newer, the production plugin, and `enderlink-<version>-native-test.jar`. Bind to `127.0.0.1` and set `online-mode=false` and `network-compression-threshold=-1`. The test plugin only accepts a local offline server.

```sh
node src/nativeTest/client.mjs 25707
```

The client uses the 26.2 protocol and a five-minute timeout. When `ENDER_TEST_RESPAWN_OK_RESTART_REQUIRED` appears, keep the client running and restart the same test server once.

Both server and client must finish with `ENDER_TEST_PASS`, and the client must exit with code 0. Coverage includes player commands, network requests, native event cancellation, legacy item hints, vanilla titles, all supported capacities, item interaction, saved data, reconnects, respawns, restarts, and persistence after emptying a chest.
