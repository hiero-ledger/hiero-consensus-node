# New Transaction / Query Checklist

Every time a new `HederaFunctionality` is added to Hiero, the items below must be completed.
Skipping any item risks shipping an unthrottled, unpermissioned, or undiscoverable operation.

> **Default posture: locked down.** A new functionality that is not explicitly added to
> throttle configs, permission mappings, and feature-flag sets is effectively unlimited
> and unrestricted at ingest. Always wire everything up before merging the handler.

---

## 1. Protobuf definitions

- [ ] Create the transaction/query body `.proto` file (e.g., `clpr_submit_bundle.proto`)
- [ ] Add a `oneof` field in `transaction_body.proto` (transactions) or the appropriate
  query wrapper (queries)
- [ ] Add an enum value to `HederaFunctionality` in `basic_types.proto`
- [ ] Regenerate protobuf sources and verify PBJ codegen picks up the new types

## 2. API permissions

- [ ] **`ApiPermissionConfig.java`** — add a `@ConfigProperty` record parameter
  (e.g., `clprSubmitBundle`) with an appropriate default range (`"0-*"` for open,
  `"2-55"` for system-only)
- [ ] **`ApiPermissionConfig.java`** — add `permissionKeys.put(FUNCTIONALITY, c -> c.field)`
  in the static initializer
- [ ] Verify `ApiPermissionConfigTest.testHederaFunctionalityUsage` passes (it iterates
  every `HederaFunctionality` and will fail if a mapping is missing)

## 3. Ingest feature flag (if feature-gated)

- [ ] **`IngestChecker.java`** — add the functionality to the appropriate feature-flag set
  so transactions are rejected at ingest when the feature is disabled:
  - `CLPR_TRANSACTIONS` — CLPR protocol operations
  - `HOOK_TRANSACTIONS` — hook-related operations
  - `UNSUPPORTED_TRANSACTIONS` — permanently disabled / deprecated
  - `PRIVILEGED_TRANSACTIONS` — system-account-only operations
- [ ] Add the config check in `assertThrottlingPreconditions()` (e.g.,
  `!clprConfig.enabled() && CLPR_TRANSACTIONS.contains(function)`)

## 4. Throttle configuration

- [ ] **`ExpectedCustomThrottles.java`** — add to `ACTIVE_OPS` enum set
- [ ] **`ExpectedCustomThrottlesTest.java`** — update expected count and add `assertTrue`
  assertion for the new operation
- [ ] **Genesis throttle files** — add operation name to the appropriate throttle group:
  - `hedera-file-service-impl/src/main/resources/genesis/throttles.json`
  - `hedera-file-service-impl/src/main/resources/genesis/throttles-dev.json`
- [ ] **Network upgrade throttle files**:
  - `configuration/mainnet/upgrade/throttles.json`
  - `configuration/testnet/upgrade/throttles.json`
  - `configuration/previewnet/upgrade/throttles.json`

## 5. Fee calculator

- [ ] Create a `ServiceFeeCalculator` implementation (or reuse a parameterized one like
  `ClprFeeCalculator`)
- [ ] Register in `ServiceImpl.serviceFeeCalculators()` (e.g., `ClprServiceImpl`)
- [ ] Register in `FacilityInitModule` via a `@Provides @ElementsIntoSet` method
- [ ] Add fee schedule entries to `feeSchedules.json` files:
  - `configuration/mainnet/upgrade/feeSchedules.json`
  - `configuration/testnet/upgrade/feeSchedules.json`
  - `configuration/previewnet/upgrade/feeSchedules.json`

## 6. Handler implementation

- [ ] Create `TransactionHandler` class with:
  - `pureChecks(PureChecksContext)` — stateless validation (no config access)
  - `preHandle(PreHandleContext)` — key gathering, config-based pre-checks
  - `handle(HandleContext)` — main logic
- [ ] Add handler to the service's `Handlers` container class (e.g., `ClprHandlers`)
- [ ] Add handler parameter to `TransactionHandlers` record
- [ ] Wire in `HandleWorkflowModule.provideTransactionHandlers()`
- [ ] Add `case` in `TransactionDispatcher.getHandler()` switch

## 7. Tests

- [ ] Unit tests for the handler (`pureChecks`, `preHandle`, `handle`)
- [ ] HAPI integration tests (end-to-end via `HapiSpec`)
- [ ] Verify all existing tests pass — especially:
  - `ApiPermissionConfigTest` (exhaustive permission check)
  - `ExpectedCustomThrottlesTest` (throttle count)
  - Any test that has an exhaustive switch on `HederaFunctionality` or `DataOneOfType`

---

## Safety nets

|             Check              | Catches missing... |                       Test                        |
|--------------------------------|--------------------|---------------------------------------------------|
| `ApiPermissionConfigTest`      | Permission mapping | Iterates all `HederaFunctionality` values         |
| `ExpectedCustomThrottlesTest`  | Throttle entry     | Asserts exact `ACTIVE_OPS` count                  |
| `TransactionDispatcher` switch | Handler wiring     | Exhaustive switch — compile error if case missing |

**Gap:** There is currently no compile-time or test-time safety net that catches a missing
throttle config JSON entry. The `ExpectedCustomThrottles.ACTIVE_OPS` set validates the
*code-level* expectation, but the actual JSON files are validated only at node startup.
Consider adding a test that parses each genesis `throttles.json` and verifies every
`ACTIVE_OPS` entry appears in at least one throttle bucket.
