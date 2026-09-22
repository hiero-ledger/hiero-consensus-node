# Validation Scenarios — Moved

The standalone `ValidationScenarios.jar` is no longer produced by this Gradle build (only
`SuiteRunner.jar` and `rcdiff.jar` are built from `test-clients`).

The equivalent functionality — running `crypto`, `file`, `contract`, and `consensus` scenarios
against a target network with a bootstrap account — has been folded into
[`yahcli`](../../yahcli/README.md) under the `ivy` command. See the **Running `ivy` acceptance
tests** section of `hedera-node/yahcli/README.md`, and the implementation in
`hedera-node/yahcli/src/main/java/com/hedera/services/yahcli/commands/ivy/`
(`ValidationScenariosCommand.java`, `IvyCryptoScenarioSuite.java`, …).

The `Dockerfile` and `run/screened-launch.sh` in this directory remain for historical reference;
they refer to a `ValidationScenarios.jar` that is not built by the current Gradle configuration.
