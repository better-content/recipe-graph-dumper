# Better Content Recipe Graph

Server-authoritative Forge 1.20.1 diagnostic mod. An operator can run
`/bcgraph dump` to export the final live recipe manager, registries, tags,
effective loot tables, sampled effective villager offers, worldgen registries,
and mod list under `generated/runtime-dumps/` in the server directory. Every
file shares one snapshot ID so offline tooling can reject mixed evidence.

Trade output is explicitly sampled evidence: each effective listing is invoked
with 16 deterministic seeds for every villager type. It preserves dynamic
listing classes and representative offer NBT without claiming that a finite
sample enumerates every possible randomized offer.

The command is intentionally the only trigger. The mod performs no work during
startup, reload, or player synchronization.

Build and test the reobfuscated runtime JAR with:

```sh
./gradlew clean test reobfJar
```
