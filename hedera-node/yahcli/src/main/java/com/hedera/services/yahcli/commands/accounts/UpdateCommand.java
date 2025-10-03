// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.accounts;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.UpdateSuite;
import com.hederahashgraph.api.proto.java.Key;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import org.hiero.base.utility.CommonUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "update",
        subcommands = {HelpCommand.class},
        description = "scheduling update account key list option")
public class UpdateCommand implements Callable<Integer> {

    @ParentCommand
    AccountsCommand accountsCommand;

    @CommandLine.Option(
            names = {"--pathKeys"},
            paramLabel = "<pathKeys>",
            description = "update path keys for the account")
    String pathKeys;

    @CommandLine.Option(
            names = {"--memo"},
            paramLabel = "<memo>",
            description = "memo to use for the update command")
    String memo;

    @CommandLine.Option(
            names = {"--targetAccount"},
            paramLabel = "<targetAccount>",
            description = "target account to update")
    String targetAccount;

    @Override
    public Integer call() throws Exception {
        var config = ConfigUtils.configFrom(accountsCommand.getYahcli());

        final var effectiveMemo = memo != null ? memo : "";
        final var normalizedTargetAccount = normalizePossibleIdLiteral(config, targetAccount);
        final var effectiveTargetAccount = normalizedTargetAccount != null ? normalizedTargetAccount : "";
        final var keysPath = pathKeys != null ? pathKeys : "";

        final var effectivePublicKeys = unHexListOfKeys(readPublicKeyFromFile(keysPath));

        if (!accountsCommand.getYahcli().isScheduled()) {
            throw new CommandLine.PicocliException("you have to schedule the update command");
        }

        final var isScheduled = accountsCommand.getYahcli().isScheduled();
        final var delegate =
                new UpdateSuite(config, effectiveMemo, effectivePublicKeys, effectiveTargetAccount, isScheduled);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            config.output()
                    .info("SUCCESS - "
                            + "Scheduled update account "
                            + effectiveTargetAccount
                            + " keys "
                            + effectivePublicKeys
                            + " with memo: '"
                            + memo
                            + "'"
                            + maybeScheduleId(isScheduled, delegate));
        } else {
            config.output()
                    .warn("FAILED - "
                            + "Schedule update account "
                            + effectiveTargetAccount
                            + " keys "
                            + effectivePublicKeys
                            + " with memo: '"
                            + memo
                            + "'");
            return 1;
        }

        return 0;
    }

    private List<String> readPublicKeyFromFile(final String fileKeyPath) throws IOException {
        File file = new File(fileKeyPath);
        return Files.readLines(file, StandardCharsets.UTF_8);
    }

    private List<Key> unHexListOfKeys(final List<String> hexedKeys) {
        List<Key> unHexedKeys = Lists.newArrayList();
        for (String hexedKey : hexedKeys) {
            ByteString byteString = ByteString.copyFrom(CommonUtils.unhex(hexedKey));
            Key key = Key.newBuilder().setEd25519(byteString).build();
            unHexedKeys.add(key);
        }
        return unHexedKeys;
    }

    private String maybeScheduleId(boolean isSchedule, UpdateSuite delegate) {
        final var message = new StringBuilder();
        if (isSchedule) {
            final var scheduleId = delegate.getScheduleId().get();
            message.append(" with schedule ID ");
            message.append(
                    asEntityString(scheduleId.getShardNum(), scheduleId.getRealmNum(), scheduleId.getScheduleNum()));
        }
        return message.toString();
    }
}
