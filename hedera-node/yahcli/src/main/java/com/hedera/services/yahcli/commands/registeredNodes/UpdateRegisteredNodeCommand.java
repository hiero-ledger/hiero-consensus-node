// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.registeredNodes;

import static com.hedera.services.bdd.spec.HapiPropertySource.asBlockNodeEndpoint;
import static com.hedera.services.bdd.spec.HapiPropertySource.asMirrorNodeEndpoint;
import static com.hedera.services.bdd.spec.HapiPropertySource.asRpcRelayEndpoint;
import static com.hedera.services.yahcli.commands.nodes.NodesCommand.validateKeyAt;
import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.UpdateRegisteredNodeSuite;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "update",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Updates an existing registered node")
public class UpdateRegisteredNodeCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    RegisteredNodesCommand registeredNodesCommand;

    @CommandLine.Option(
            names = {"-n", "--nodeId"},
            paramLabel = "id of the registered node to update",
            required = true)
    String nodeId;

    @CommandLine.Option(
            names = {"-d", "--description"},
            paramLabel = "updated description for the registered node")
    @Nullable
    String description;

    @CommandLine.Option(
            names = {"--blockNodeEndpoint"},
            paramLabel = "updated block node endpoint, format: addr:port[:blockNodeApi][:tls]",
            arity = "0..*")
    @Nullable
    List<String> blockNodeEndpoints;

    @CommandLine.Option(
            names = {"--mirrorNodeEndpoint"},
            paramLabel = "updated mirror node endpoint, format: addr:port[:tls]",
            arity = "0..*")
    @Nullable
    List<String> mirrorNodeEndpoints;

    @CommandLine.Option(
            names = {"--rpcRelayEndpoint"},
            paramLabel = "updated RPC relay endpoint, format: addr:port[:tls]",
            arity = "0..*")
    @Nullable
    List<String> rpcRelayEndpoints;

    @CommandLine.Option(
            names = {"-k", "--adminKey"},
            paramLabel = "path to the current admin key to use")
    @Nullable
    String adminKeyPath;

    @CommandLine.Option(
            names = {"-nk", "--newAdminKey"},
            paramLabel = "path to the updated admin key to use")
    @Nullable
    String newAdminKeyPath;

    @Override
    public Integer call() throws Exception {
        final var yahcli = registeredNodesCommand.getYahcli();
        final var config = ConfigUtils.configFrom(yahcli);
        final var normalizedNodeId = normalizePossibleIdLiteral(config, nodeId);
        final var targetNodeId = validatedNodeId(normalizedNodeId);

        if (adminKeyPath == null) {
            config.output().warn("No --adminKey option, payer signature alone must meet signing requirements");
        } else {
            validateKeyAt(adminKeyPath, yahcli);
        }
        if (newAdminKeyPath != null) {
            validateKeyAt(newAdminKeyPath, yahcli);
        }

        final var delegate = new UpdateRegisteredNodeSuite(
                config, targetNodeId, adminKeyPath, newAdminKeyPath, description, buildEndpoints());
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            config.output().info("SUCCESS - registeredNode" + targetNodeId + " has been updated");
        } else {
            config.output().warn("FAILED to update registeredNode" + targetNodeId);
            return 1;
        }

        return 0;
    }

    @Nullable
    private List<RegisteredServiceEndpoint> buildEndpoints() {
        if (blockNodeEndpoints == null && mirrorNodeEndpoints == null && rpcRelayEndpoints == null) {
            return null;
        }
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
        return endpoints;
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
