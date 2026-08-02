# Better Content Recipe Graph

Server-authoritative Forge 1.20.1 diagnostic mod. An operator can run
`/bcgraph dump` to export the final live recipe manager, registries, tags, and
mod list under `generated/runtime-dumps/` in the server directory.

The command is intentionally the only trigger. The mod performs no work during
startup, reload, or player synchronization.

Build and test the reobfuscated runtime JAR with:

```sh
./gradlew clean test reobfJar
```
