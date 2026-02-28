# Amendments

## A-1: V0710TokenSchema requires pre-existing NATIVE_COIN_DECIMALS singleton
- Story: 1-2 | Date: 2026-02-28
- Original: Architecture does not specify rolling-upgrade behavior for the new singleton
- Actual: V0710TokenSchema.migrate() throws IllegalStateException if the NATIVE_COIN_DECIMALS singleton is missing from previous state on non-genesis startup
- Rationale: Per DD-3 (LOCKED), native coin decimals is a greenfield feature. The singleton is created at genesis by TokenServiceImpl.doGenesisSetup(). A pre-0.71.0 node upgrading would not have this state. This is acceptable because the feature targets new network deployments only, not rolling upgrades of existing networks. If rolling-upgrade support is needed later, migrate() should be changed to write a default value instead of throwing.
