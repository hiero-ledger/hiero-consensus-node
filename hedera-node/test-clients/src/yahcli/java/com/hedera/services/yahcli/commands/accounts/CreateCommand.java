// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.accounts;

import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;
import static com.hedera.services.yahcli.suites.CreateSuite.NOVELTY;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.CreateSuite;
import com.hedera.services.yahcli.util.ParseUtils;
import java.io.File;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "create",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Creates a new account with a simple Ed25519 key")
public class CreateCommand implements Callable<Integer> {
    private static final int DEFAULT_NUM_RETRIES = 5;

    @CommandLine.ParentCommand
    AccountsCommand accountsCommand;

    @CommandLine.Option(
            names = {"-m", "--memo"},
            paramLabel = "Memo for new account")
    String memo;

    @CommandLine.Option(
            names = {"-r", "--retries"},
            paramLabel = "Number of times to retry on BUSY")
    Integer boxedRetries;

    @CommandLine.Option(
            names = {"-a", "--amount"},
            paramLabel = "<initial balance>",
            description = "how many units of the denomination to use as initial balance",
            defaultValue = "0")
    String amountRepr;

    @CommandLine.Option(
            names = {"-d", "--denomination"},
            paramLabel = "denomination",
            description = "{ tinybar | hbar | kilobar }",
            defaultValue = "hbar")
    String denomination;

    @CommandLine.Option(
            names = {"-S", "--receiverSigRequired"},
            paramLabel = "receiverSigRequired",
            description = "If receiver signature is required")
    boolean receiverSigRequired;

    @CommandLine.Option(
            names = {"-k", "--keyType"},
            paramLabel = "keyType",
            description = "Type of key to use for the new account: ED25519 or SECP256K1",
            defaultValue = "ED25519")
    String keyType;

    @Override
    public Integer call() throws Exception {
        final var yahcli = accountsCommand.getYahcli();
        var config = ConfigUtils.configFrom(yahcli);

        final var noveltyLoc = config.keysLoc() + File.separator + NOVELTY + ".pem";
        final SigControl sigType = ParseUtils.keyTypeFromParam(keyType);
        // Since we are creating an account, we expect the caller to explicitly specify the key type
        if (sigType == null) {
            COMMON_MESSAGES.warn("Invalid key type: " + keyType + ". Must be 'ED25519' or 'SECP256K1'");
            return 1;
        }
        final var effectiveMemo = memo != null ? memo : "";
        final var amount = SendCommand.validatedTinybars(yahcli, amountRepr, denomination);
        final var retries = boxedRetries != null ? boxedRetries.intValue() : DEFAULT_NUM_RETRIES;
        final var effectiveReceiverSigRequired = receiverSigRequired;

        final var delegate = new CreateSuite(
                config.asSpecConfig(),
                amount,
                effectiveMemo,
                noveltyLoc,
                sigType,
                retries,
                effectiveReceiverSigRequired);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().get(0).getStatus() == HapiSpec.SpecStatus.PASSED) {
            COMMON_MESSAGES.info("SUCCESS - account "
                    + HapiSuite.DEFAULT_SHARD_REALM
                    + +delegate.getCreatedNo().get()
                    + " has been created with balance "
                    + amount
                    + " tinybars "
                    + ", signatureRequired "
                    + effectiveReceiverSigRequired
                    + " and memo '"
                    + effectiveMemo
                    + "'");
        } else {
            COMMON_MESSAGES.warn("FAILED to create a new account with "
                    + amount
                    + " tinybars "
                    + ", signatureRequired "
                    + effectiveReceiverSigRequired
                    + " and memo '"
                    + effectiveMemo
                    + "'");
            return 1;
        }

        return 0;
    }
}
