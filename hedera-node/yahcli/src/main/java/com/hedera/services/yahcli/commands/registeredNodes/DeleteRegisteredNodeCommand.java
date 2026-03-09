// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.registeredNodes;

import static com.hedera.services.yahcli.commands.nodes.NodesCommand.validateKeyAt;
import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.DeleteRegisteredNodeSuite;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "delete",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Delete a registered node")
public class DeleteRegisteredNodeCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    RegisteredNodesCommand registeredNodesCommand;

    @CommandLine.Option(
            names = {"-n", "--nodeId"},
            paramLabel = "id of the registered node to delete",
            required = true)
    String nodeId;

    @CommandLine.Option(
            names = {"-k", "--adminKey"},
            paramLabel = "path to the admin key to use")
    @Nullable
    String adminKeyPath;

    @Override
    public Integer call() throws Exception {
        final var yahcli = registeredNodesCommand.getYahcli();
        final var config = ConfigUtils.configFrom(yahcli);
        final var normalizedNodeId = normalizePossibleIdLiteral(config, nodeId);
        final var targetId = validatedNodeId(normalizedNodeId);

        if (adminKeyPath == null) {
            config.output().warn("No --adminKey option, payer signature alone must meet signing requirements");
        } else {
            validateKeyAt(adminKeyPath, yahcli);
        }

        final var delegate = new DeleteRegisteredNodeSuite(config, targetId, adminKeyPath);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            config.output().info("SUCCESS - registeredNode" + targetId + " has been deleted");
        } else {
            config.output().warn("FAILED to delete registeredNode" + targetId);
            return 1;
        }

        return 0;
    }

    private long validatedNodeId(@NonNull final String nodeId) {
        try {
            return Long.parseLong(nodeId);
        } catch (Exception e) {
            throw new CommandLine.ParameterException(
                    registeredNodesCommand.getYahcli().getSpec().commandLine(),
                    "Invalid registered node id '" + nodeId + "'");
        }
    }
}
