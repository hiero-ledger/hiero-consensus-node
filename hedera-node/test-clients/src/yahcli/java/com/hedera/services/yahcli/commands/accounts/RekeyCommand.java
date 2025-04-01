// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.accounts;

import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

import com.google.common.io.Files;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.RekeySuite;
import com.hedera.services.yahcli.util.ParseUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "rekey",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Replaces the key on an account")
public class RekeyCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    AccountsCommand accountsCommand;

    @CommandLine.Option(
            names = {"-k", "--replacement-key"},
            paramLabel = "path to new key file",
            defaultValue = "<N/A>")
    String replKeyLoc;

    @CommandLine.Option(
            names = {"-g", "--gen-new-key"},
            paramLabel = "auto-generate new key",
            defaultValue = "false")
    boolean genNewKey;

    @CommandLine.Option(
            names = {"-K", "--keyType"},
            paramLabel = "keyType",
            description = "Type of key to generate: ED25519 or SECP256K1 (only applies with -g)",
            defaultValue = "ED25519")
    String keyType;

    @CommandLine.Parameters(arity = "1", paramLabel = "<account>", description = "number of account to rekey")
    String accountNum;

    @Override
    public Integer call() throws Exception {
        var config = ConfigUtils.configFrom(accountsCommand.getYahcli());

        if ("<N/A>".equals(replKeyLoc) && !genNewKey) {
            throw new CommandLine.ParameterException(
                    accountsCommand.getYahcli().getSpec().commandLine(),
                    "Must set --gen-new-key if no --replacement-key given");
        }

        final var optKeyFile = backupCurrentAssets(config, accountNum);
        final String replTarget = optKeyFile
                .map(File::getPath)
                .orElseGet(() -> config.keysLoc() + File.separator + "account" + accountNum + ".pem");

        final SigControl sigType =
                Optional.ofNullable(ParseUtils.keyTypeFromParam(keyType)).orElse(SigControl.ED25519_ON);
        final var delegate =
                new RekeySuite(config.asSpecConfig(), accountNum, replKeyLoc, genNewKey, sigType, replTarget);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().get(0).getStatus() == HapiSpec.SpecStatus.PASSED) {
            COMMON_MESSAGES.info("SUCCESS - account 0.0." + accountNum + " has been re-keyed");
        } else {
            COMMON_MESSAGES.warn("FAILED to re-key account 0.0." + accountNum);
            return 1;
        }

        return 0;
    }

    private Optional<File> backupCurrentAssets(ConfigManager configManager, String num) throws IOException {
        var optKeyFile = ConfigUtils.keyFileFor(configManager.keysLoc(), "account" + num);
        if (optKeyFile.isPresent()) {
            final var keyFile = optKeyFile.get();
            final var keyLoc = keyFile.getAbsolutePath();
            final var backupLoc = keyLoc + ".bkup";
            Files.copy(keyFile, java.nio.file.Files.newOutputStream(Paths.get(backupLoc)));
            if (keyLoc.endsWith(".pem")) {
                final var optPassFile = AccessoryUtils.passFileFor(keyFile);
                if (optPassFile.isPresent()) {
                    final var passFile = optPassFile.get();
                    final var passBackupLoc = passFile.getAbsolutePath() + ".bkup";
                    Files.copy(passFile, java.nio.file.Files.newOutputStream(Paths.get(passBackupLoc)));
                }
            }
        } else {
            COMMON_MESSAGES.warn("No current key for account " + num + ", payer will need special privileges");
        }
        return optKeyFile;
    }
}
