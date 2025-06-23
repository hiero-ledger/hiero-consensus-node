// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.nodes;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.services.bdd.spec.HapiPropertySource.asCsServiceEndpoints;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asTypedServiceEndpoint;
import static com.hedera.services.yahcli.commands.nodes.CreateCommand.allBytesAt;
import static com.hedera.services.yahcli.commands.nodes.NodesCommand.validateKeyAt;
import static com.hedera.services.yahcli.commands.nodes.NodesCommand.validatedX509Cert;
import static com.hedera.services.yahcli.config.ConfigUtils.keyFileFor;
import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;
import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.UpdateNodeSuite;
import com.hederahashgraph.api.proto.java.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "update",
        subcommands = {CommandLine.HelpCommand.class},
        description = "update an existing node")
public class UpdateCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    NodesCommand nodesCommand;

    @CommandLine.Option(
            names = {"-n", "--nodeId"},
            paramLabel = "id of the node to update")
    String nodeId;

    @CommandLine.Option(
            names = {"-a", "--accountNum"},
            paramLabel = "updated number of the node's fee collection account id, e.g. 3 for 0.0.3")
    @Nullable
    String accountId;

    @CommandLine.Option(
            names = {"-d", "--description"},
            paramLabel = "updated description for the node")
    @Nullable
    String description;

    @CommandLine.Option(
            names = {"-g", "--gossipEndpoints"},
            paramLabel = "updated comma-delimited gossip endpoints, e.g. 10.0.0.1:50070,my.fqdn.com:50070")
    String gossipEndpoints;

    @CommandLine.Option(
            names = {"-s", "--serviceEndpoints"},
            paramLabel = "updated comma-delimited gossip endpoints, e.g. 10.0.0.1:50211,my.fqdn.com:50211")
    String serviceEndpoints;

    @CommandLine.Option(
            names = {"-c", "--gossipCaCertificate"},
            paramLabel = "path to the updated X.509 CA certificate for node's gossip key")
    String gossipCaCertificatePath;

    @CommandLine.Option(
            names = {"-x", "--gossipCaCertificatePfx"},
            paramLabel = "path to a .pfx (PKCS#12) file with a X.509 CA certificate for the node's gossip key")
    String gossipCaCertificatePfxPath;

    @CommandLine.Option(
            names = {"-l", "--gossipCaCertificateAlias"},
            paramLabel = "alias in the given .pfx (PKCS#12) file for the X.509 CA certificate of the node's gossip key")
    String gossipCaCertificatePfxAlias;

    @CommandLine.Option(
            names = {"-h", "--hapiCertificate"},
            paramLabel = "path to the updated self-signed X.509 certificate for node's HAPI TLS endpoint")
    String hapiCertificatePath;

    @CommandLine.Option(
            names = {"-k", "--adminKey"},
            paramLabel = "path to the current admin key to use")
    String adminKeyPath;

    @CommandLine.Option(
            names = {"-nk", "--newAdminKey"},
            paramLabel = "path to the updated admin key to use")
    String newAdminKeyPath;

    @CommandLine.Option(
            names = {"--stop-declining-rewards", "--stopDecliningRewards"},
            paramLabel = "triggers a node to begin accepting reward payments")
    Boolean stopDecliningRewards;

    @CommandLine.Option(
            names = {"--start-declining-rewards", "--startDecliningRewards"},
            paramLabel = "triggers a node to begin declining reward payments")
    Boolean startDecliningRewards;

    @CommandLine.Option(
            names = {"--grpcProxyEndpoint"},
            paramLabel =
                    "updated web proxy endpoint for gRPC from non-gRPC clients, e.g. 10.0.0.1:50051,my.fqdn.com:50051")
    String grpcProxyEndpoint;

    @Override
    public Integer call() throws Exception {
        final var yahcli = nodesCommand.getYahcli();
        var config = ConfigUtils.configFrom(yahcli);
        final var normalizedNodeId = normalizePossibleIdLiteral(config, nodeId);
        final var targetNodeId = validatedNodeId(normalizedNodeId);
        final AccountID newAccountId;
        final String feeAccountKeyLoc;
        final List<ServiceEndpoint> newGossipEndpoints;
        final List<ServiceEndpoint> newHapiEndpoints;
        final byte[] newGossipCaCertificate;
        final byte[] newHapiCertificateHash;
        final ServiceEndpoint newGrpcProxyEndpoint;
        final var normalizedAccountId = normalizePossibleIdLiteral(config, accountId);
        if (normalizedAccountId == null) {
            newAccountId = null;
            feeAccountKeyLoc = null;
        } else {
            newAccountId = validatedAccountId(
                    config.shard().getShardNum(), config.realm().getRealmNum(), normalizedAccountId);
            final var feeAccountKeyFile = keyFileFor(config.keysLoc(), "account" + newAccountId.getAccountNum());
            feeAccountKeyLoc = feeAccountKeyFile.map(File::getPath).orElse(null);
            if (feeAccountKeyLoc == null) {
                COMMON_MESSAGES.warn("No key on disk for account " + newAccountId.getAccountNum()
                        + ", payer and admin key signatures must meet its signing requirements");
            }
        }
        if (adminKeyPath == null) {
            COMMON_MESSAGES.warn("No --adminKey option, payer signature alone must meet signing requirements");
        } else {
            validateKeyAt(adminKeyPath, yahcli);
        }
        if (newAdminKeyPath != null) {
            validateKeyAt(newAdminKeyPath, yahcli);
        }
        if (gossipEndpoints != null) {
            newGossipEndpoints = asCsServiceEndpoints(gossipEndpoints);
        } else {
            newGossipEndpoints = null;
        }
        if (serviceEndpoints != null) {
            newHapiEndpoints = asCsServiceEndpoints(serviceEndpoints);
        } else {
            newHapiEndpoints = null;
        }
        if (grpcProxyEndpoint != null) {
            newGrpcProxyEndpoint = asTypedServiceEndpoint(grpcProxyEndpoint);
        } else {
            newGrpcProxyEndpoint = null;
        }
        if (gossipCaCertificatePath != null) {
            newGossipCaCertificate = validatedX509Cert(gossipCaCertificatePath, null, null, yahcli);
        } else if (gossipCaCertificatePfxPath != null) {
            newGossipCaCertificate =
                    validatedX509Cert(null, gossipCaCertificatePfxPath, gossipCaCertificatePfxAlias, yahcli);
        } else {
            newGossipCaCertificate = null;
        }
        if (hapiCertificatePath != null) {
            // Throws if the cert is not valid
            validatedX509Cert(hapiCertificatePath, null, null, yahcli);
            newHapiCertificateHash = noThrowSha384HashOf(allBytesAt(Paths.get(hapiCertificatePath)));
        } else {
            newHapiCertificateHash = null;
        }
        Boolean declineReward = null;
        if (startDecliningRewards != null) {
            declineReward = Boolean.TRUE;
        } else if (stopDecliningRewards != null) {
            declineReward = Boolean.FALSE;
        }
        final var delegate = new UpdateNodeSuite(
                config,
                targetNodeId,
                newAccountId,
                feeAccountKeyLoc,
                adminKeyPath,
                newAdminKeyPath,
                description,
                newGossipEndpoints,
                newHapiEndpoints,
                newGossipCaCertificate,
                newHapiCertificateHash,
                declineReward,
                newGrpcProxyEndpoint);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            COMMON_MESSAGES.info("SUCCESS - node" + targetNodeId + " has been updated");
        } else {
            COMMON_MESSAGES.warn("FAILED to update node" + targetNodeId);
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

    private AccountID validatedAccountId(final long shard, final long realm, @NonNull final String accountNum) {
        try {
            return AccountID.newBuilder()
                    .setShardNum(shard)
                    .setRealmNum(realm)
                    .setAccountNum(Long.parseLong(accountNum))
                    .build();
        } catch (NumberFormatException e) {
            throw new CommandLine.ParameterException(
                    nodesCommand.getYahcli().getSpec().commandLine(),
                    "Invalid account number '" + asEntityString(shard, realm, accountNum) + "'");
        }
    }
}
