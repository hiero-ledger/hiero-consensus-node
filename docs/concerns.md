# Concerns

## C-1: DenominationConverter error message uses field name, not config property name
- **Date**: 2026-02-28
- **Story**: 1-1
- **Severity**: LOW
- **Detail**: `DenominationConverter` constructor error says "decimals must be in range..." using the field name, not the config property name `nativeCoin.decimals`. This is acceptable because `DenominationConverter` is a general utility, not config-specific. The config-level `@Min`/`@Max` validation in `NativeCoinConfig` provides the config property name via `ConfigViolationException`.
- **Action**: No change needed — informational only.

## C-2: FinalizeRecordHandler parameterized test uses indirect assertion
- **Date**: 2026-03-02
- **Story**: 2-2
- **Severity**: LOW
- **Detail**: The `derivesLedgerTotalTinyBarFloatFromDenominationConverter` test validates the derived value indirectly through the handler's internal equality check. A failure produces `IllegalStateException("Invalid hbar changes from child record")` rather than a clear assertion message. This works correctly but makes debugging regressions less straightforward.
- **Action**: Consider adding a comment documenting the test mechanism or exposing the derived value via a package-private getter in a future story.

## C-3: TOTAL_SUPPLY_WHOLE_UNITS is hardcoded, not configurable
- **Date**: 2026-03-02
- **Story**: 2-2
- **Severity**: LOW
- **Detail**: `TOTAL_SUPPLY_WHOLE_UNITS = 50_000_000_000L` is a compile-time constant. If a future network needs a different total supply, this requires a code change. Within scope for this story (ACs specify this value), but limits future configurability.
- **Action**: Consider making whole-unit supply configurable via `LedgerConfig` in a future story if multi-network support requires it.
