# Changelog

## Unreleased

### Changed

- Added a client-only `/runtimedata atlas` export for complete live-rendered registry and FTB Quests icon evidence.
- Added exact live block-state and fluid light emission plus declared Sodium Dynamic Lights portable-item evidence to `/runtimedata dump`.
- Standardized the project as **Runtime Data Dumper** with mod ID `runtime_data_dumper`, artifact `runtime-data-dumper`, and package `com.bettercontent.runtimedatadumper`.
- Adopted Java 17 and Forge 1.20.1-47.4.13 as the build baseline without changing the project version.
- This is a clean break; legacy worlds, configurations, and integrations are not migrated.
