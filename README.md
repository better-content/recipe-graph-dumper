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

`snapshot.json` is complete only when every live recipe has a fully normalized
machine edge, every serializer payload succeeds, and the exact loot/worldgen
plus sampled-trade export contracts report no errors. Incomplete rows and raw
serializer payloads are retained for adapter work, but the command returns a
failure result and pack tooling must not promote or fingerprint that snapshot
as authoritative. Worldgen registry data proves configured live state, not
placement frequency or occurrence in a generated world; loaded loot tables
likewise do not prove that their runtime context is reachable.

Optional recipe adapters only emit edges backed by live accessors or pinned
serializer state. In addition to item and fluid edges, the graph can therefore
name block transformations, TConstruct materials/modifiers/modifier slots, and
enchantments without pretending those state transitions are ordinary items.
Context-dependent rules use an explicit `operation_kind`, typed `effects`, and
typed `requirements`: Blood Magic flask state changes, TConstruct material-cost
melting and recycling/tool mutations, and AE2 matter-cannon ammo profiles are
therefore navigable without fake static outputs. AlmostUnified client recipe
trackers remain partial because synchronization metadata is not gameplay.

The command is intentionally the only trigger. The mod performs no work during
startup, reload, or player synchronization.

Build and test the reobfuscated runtime JAR with:

```sh
./gradlew clean test reobfJar
```
