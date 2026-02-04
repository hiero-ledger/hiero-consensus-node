---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-01-28
------------------------

# Current CLPR implementation (verified)

## Scope

The current implementation is a dev-mode prototype focused on exchanging
ledger configurations (roster + endpoints) between networks. Message queue
metadata/content exchange, payments, and full connector economics are not
implemented yet.

## Verified capabilities

- CLPR gRPC surface and protobuf definitions for get/set ledger configuration.
- CLPR state stores for ledger configurations and local metadata.
- Synthetic CLPR ledger configuration dispatch on bootstrap/roster changes.
- Dev-mode state proofs built from the latest immutable Merkle state snapshot.
- Endpoint client loop that publishes local configs and pulls newer remote configs.
- CLPR state changes emitted through block stream state change output.
- Multi-network HAPI support and Ship of Theseus test suite for configuration exchange.

## Dev-mode shortcuts (verified)

- State proofs use local Merkle state and treat the state root hash as the signature.
- Ingest bypasses payer signature enforcement for CLPR_SET_LEDGER_CONFIG in dev mode.
- CLPR client uses a dev-mode signer and falls back to empty signature maps if no key is found.

## Current limitations (verified)

- Queue metadata/content exchange is stubbed (TODO) in the endpoint client.
- Endpoint exchange requires a published service endpoint in the remote config.
- Endpoint client skips publish/pull until a local ledger id is available.

## Key implementation touchpoints

- Bootstrap dispatch: SystemTransactions.maybeDispatchClprBootstrap
- Endpoint loop: ClprEndpointClient
- State proofs: ClprStateProofManager + StateProofBuilder/Verifier
- CLPR handlers: ClprSetLedgerConfigurationHandler, ClprGetLedgerConfigurationHandler
- Ingest shortcut: IngestChecker.verifyPayerSignature
- Dev signer: ClprClientImpl.DevTransactionSigner
- Block stream output: ImmediateStateChangeListener, BoundaryStateChangeListener

## Related prototype docs (to be moved or integrated)

- clpr-overview.md
- hapi-test-framework-changes.md
- ship-of-theseus-suite.md
