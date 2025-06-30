// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.accounts;

import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.InfoSuite;
import java.util.Arrays;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "info",
        subcommands = {HelpCommand.class},
        description = "Checks account info")
public class InfoCommand implements Callable<Integer> {
    @ParentCommand
    AccountsCommand accountsCommand;

    @Parameters(arity = "1..*", paramLabel = "<accounts>", description = "account names or numbers")
    String[] accounts;

    @Override
    public Integer call() throws Exception {
        var config = ConfigUtils.configFrom(accountsCommand.getYahcli());

        StringBuilder balanceRegister = new StringBuilder();
        String serviceBorder = "---------------------|----------------------|";
        balanceRegister.append(serviceBorder).append("\n");
        balanceRegister.append(String.format("%20s | %20s |%n", "Account Id", "Keys"));
        balanceRegister.append(serviceBorder);

        printTable(balanceRegister);

        final var normalizedAccounts = Arrays.stream(accounts)
                .map(s -> normalizePossibleIdLiteral(config, s))
                .toArray(String[]::new);
        var delegate = new InfoSuite(config.asSpecConfig(), normalizedAccounts);
        delegate.runSuiteSync();

        return 0;
    }

    private void printTable(final StringBuilder balanceRegister) {
        System.out.println(balanceRegister.toString());
    }
}
