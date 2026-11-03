# CLPR End-to-End Tests

This doc compiles all the CLPR end-to-end (E2E) test walkthroughs — runnable, step-by-step tutorials that
exercise a full feature across real local networks.

- **Automated JUnit e2e tests.** Search this repository for test files tagged with `@Tag(MULTINETWORK)` to get a list of all the automated multinetwork e2e tests. They will contain only hiero-to-hiero deployments.
- [Hiero-to-Hiero PingPong](../../hedera-node/yahcli/scripts/clpre2e/hiero-to-hiero-ping-pong-test.md) — a complete
  cross-ledger message round-trip between two local Hiero networks. Deploys and runs a "ping-pong" app (smart contract) on top of the CLPR protocol.
- [Hiero-To-Besu Simple Message](../../hedera-node/yahcli/scripts/clpre2e/hiero-to-besu-simple-message.md) - a complete cross-ledger message round-trip between a local Hiero network and a local Besu network.
- [Hiero-To-Sei Simple Message](../..//hedera-node/yahcli/scripts/clpre2e/hiero-to-sei-simple-message.md) - a complete cross-ledger message round-trip between a local Hiero network and a local Sei network.
