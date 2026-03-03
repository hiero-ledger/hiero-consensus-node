// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.registeredNodes;

import static com.hedera.services.bdd.spec.HapiPropertySource.asBlockNodeEndpoint;
import static com.hedera.services.bdd.spec.HapiPropertySource.asMirrorNodeEndpoint;
import static com.hedera.services.bdd.spec.HapiPropertySource.asRpcRelayEndpoint;
import static com.hedera.services.yahcli.commands.nodes.NodesCommand.validateKeyAt;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.CreateRegisteredNodeSuite;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "create",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Creates a new registered node")
public class CreateRegisteredNodeCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    RegisteredNodesCommand registeredNodesCommand;

    @CommandLine.Option(
            names = {"-d", "--description"},
            paramLabel = "description for the new registered node")
    @Nullable
    String description;

    @CommandLine.Option(
            names = {"--blockNodeEndpoint"},
            paramLabel = "block node endpoint, format: addr:port[:blockNodeApi][:tls]",
            arity = "0..*")
    List<String> blockNodeEndpoints;

    @CommandLine.Option(
            names = {"--mirrorNodeEndpoint"},
            paramLabel = "mirror node endpoint, format: addr:port[:tls]",
            arity = "0..*")
    List<String> mirrorNodeEndpoints;

    @CommandLine.Option(
            names = {"--rpcRelayEndpoint"},
            paramLabel = "RPC relay endpoint, format: addr:port[:tls]",
            arity = "0..*")
    List<String> rpcRelayEndpoints;

    @CommandLine.Option(
            names = {"-k", "--adminKey"},
            paramLabel = "path to the admin key to use",
            required = true)
    String adminKeyPath;

    @Override
    public Integer call() throws Exception {
        final var yahcli = registeredNodesCommand.getYahcli();
        final var config = ConfigUtils.configFrom(yahcli);

        validateKeyAt(adminKeyPath, yahcli);

        final List<RegisteredServiceEndpoint> endpoints = new ArrayList<>();

        if (blockNodeEndpoints != null) {
            for (final var s : blockNodeEndpoints) {
                endpoints.add(asBlockNodeEndpoint(s));
            }
        }
        if (mirrorNodeEndpoints != null) {
            for (final var s : mirrorNodeEndpoints) {
                endpoints.add(asMirrorNodeEndpoint(s));
            }
        }
        if (rpcRelayEndpoints != null) {
            for (final var s : rpcRelayEndpoints) {
                endpoints.add(asRpcRelayEndpoint(s));
            }
        }

        if (endpoints.isEmpty()) {
            throw new CommandLine.ParameterException(
                    yahcli.getSpec().commandLine(),
                    "At least one endpoint (--blockNodeEndpoint, --mirrorNodeEndpoint, or --rpcRelayEndpoint) is required");
        }

        final var delegate = new CreateRegisteredNodeSuite(config, description, endpoints, adminKeyPath);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            config.output().info("SUCCESS - created registeredNode" + delegate.createdIdOrThrow());
        } else {
            config.output().warn("FAILED to create registeredNode");
            return 1;
        }

        return 0;
    }
}
