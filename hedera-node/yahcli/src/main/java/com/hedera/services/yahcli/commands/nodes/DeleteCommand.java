// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.nodes;

import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.DeleteNodeSuite;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "delete",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Delete a node")
public class DeleteCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    NodesCommand nodesCommand;

    @CommandLine.Option(
            names = {"-n", "--nodeId"},
            paramLabel = "node id for deletion")
    String nodeId;

    @CommandLine.Option(
            names = {"-k", "--adminKey"},
            paramLabel = "path to the admin key to use")
    @Nullable
    String adminKeyPath;

    @Override
    public Integer call() throws Exception {
        final var yahcli = nodesCommand.getYahcli();
        var config = ConfigUtils.configFrom(yahcli);
        var normalizedNodeId = normalizePossibleIdLiteral(config, nodeId);
        final var targetId = validatedNodeId(normalizedNodeId);

        if (adminKeyPath == null) {
            config.output().warn("No --adminKey option, payer signature alone must meet signing requirements");
        } else {
            NodesCommand.validateKeyAt(adminKeyPath, yahcli);
        }

        final var delegate = new DeleteNodeSuite(config, targetId, adminKeyPath);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            config.output().info("SUCCESS - node" + normalizedNodeId + " has been deleted");
        } else {
            config.output().warn("FAILED to delete node" + normalizedNodeId);
            return 1;
        }

        return 0;
    }

    private long validatedNodeId(@NonNull final String nodeId) {
        try {
            return Long.parseLong(nodeId);
        } catch (Exception e) {
            throw new CommandLine.ParameterException(
                    nodesCommand.getYahcli().getSpec().commandLine(), "Invalid node id '" + nodeId + "'");
        }
    }
}
