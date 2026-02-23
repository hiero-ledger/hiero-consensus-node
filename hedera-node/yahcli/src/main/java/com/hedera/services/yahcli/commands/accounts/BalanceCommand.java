// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.accounts;

import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.BalanceSuite;
import java.util.Arrays;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "balance",
        subcommands = {HelpCommand.class},
        description = "Checks account balances")
public class BalanceCommand implements Callable<Integer> {
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
        balanceRegister.append(String.format("%20s | %20s |%n", "Account Id", "Balance"));
        balanceRegister.append(serviceBorder);

        printTable(config, balanceRegister);

        final var normalizedAccounts = Arrays.stream(accounts)
                .map(s -> normalizePossibleIdLiteral(config, s))
                .toArray(String[]::new);
        var delegate = new BalanceSuite(config.asSpecConfig(), normalizedAccounts);
        delegate.runSuiteSync();

        delegate.balancesByRepr()
                .forEach((repr, balance) -> config.output().info(String.format("%20s | %20d |", repr, balance)));

        return 0;
    }

    private void printTable(ConfigManager config, final StringBuilder balanceRegister) {
        config.output().info(balanceRegister.toString());
    }
}
