// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.fees;

import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.CostOfEveryThingSuite;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "list-base-prices",
        subcommands = {CommandLine.HelpCommand.class},
        description = "List base prices for all operations")
public class FeeBasePriceCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    FeesCommand feesCommand;

    @CommandLine.Parameters(
            arity = "1..*",
            paramLabel = "<services>",
            description = "services ('crypto', 'consensus', 'token', 'file', 'contract', 'scheduled')  \n"
                    + "or 'all' to get fees for all basic operations ")
    String[] services;

    @Override
    public Integer call() throws Exception {
        var config = ConfigUtils.configFrom(feesCommand.getYahcli());

        StringBuilder feeTableSB = new StringBuilder();
        String serviceBorder = "-------------------------------|-----------------|\n";
        feeTableSB.append(serviceBorder);
        feeTableSB.append(String.format("%30s |  \t\t |%n", "Transaction and Query Fees"));
        feeTableSB.append(serviceBorder);

        var delegate = new CostOfEveryThingSuite(config, feeTableSB, serviceBorder, services);
        delegate.runSuiteSync();

        printTable(feeTableSB);

        return 0;
    }

    private void printTable(final StringBuilder feeTableSB) {
        System.out.println(feeTableSB.toString());
    }
}
