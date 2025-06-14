// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.nodes;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.services.bdd.spec.HapiPropertySource.asCsServiceEndpoints;
import static com.hedera.services.bdd.spec.HapiPropertySource.asTypedServiceEndpoint;
import static com.hedera.services.yahcli.commands.nodes.NodesCommand.validatedX509Cert;
import static com.hedera.services.yahcli.config.ConfigUtils.keyFileFor;
import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;
import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.CreateNodeSuite;
import com.hederahashgraph.api.proto.java.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "create",
        subcommands = {CommandLine.HelpCommand.class},
        description = "Creates a new node")
public class CreateCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    NodesCommand nodesCommand;

    @CommandLine.Option(
            names = {"-a", "--accountNum"},
            paramLabel = "the number of the node's fee collection account id, e.g. 3 for 0.0.3")
    String accountNum;

    @CommandLine.Option(
            names = {"-d", "--description"},
            paramLabel = "description for the new node")
    @Nullable
    String description;

    @CommandLine.Option(
            names = {"-g", "--gossipEndpoints"},
            paramLabel = "comma-delimited gossip endpoints, e.g. 10.0.0.1:50070,my.fqdn.com:50070")
    String gossipEndpoints;

    @CommandLine.Option(
            names = {"-s", "--serviceEndpoints"},
            paramLabel = "comma-delimited gossip endpoints, e.g. 10.0.0.1:50211,my.fqdn.com:50211")
    String serviceEndpoints;

    @CommandLine.Option(
            names = {"-c", "--gossipCaCertificate"},
            paramLabel = "path to the X.509 CA certificate for the node's gossip key")
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
            paramLabel = "path to the self-signed X.509 certificate for node's HAPI TLS endpoint")
    String hapiCertificatePath;

    @CommandLine.Option(
            names = {"-k", "--adminKey"},
            paramLabel = "path to the admin key to use")
    String adminKeyPath;

    @CommandLine.Option(
            names = "--declineRewards",
            paramLabel = "trigger indicating the node should decline reward payments; false otherwise",
            arity = "0..1",
            defaultValue = "true",
            fallbackValue = "true")
    Boolean declineRewards;

    @CommandLine.Option(
            names = {"--grpcProxyEndpoint"},
            paramLabel = "a web proxy endpoint for gRPC from non-gRPC clients, e.g. 10.0.0.1:50051,my.fqdn.com:50051")
    String grpcProxyEndpoint;

    @Override
    public Integer call() throws Exception {
        final var yahcli = nodesCommand.getYahcli();
        var config = ConfigUtils.configFrom(yahcli);

        validateAdminKeyLoc(adminKeyPath);
        final var normalizedAcctNum = normalizePossibleIdLiteral(config, accountNum);
        final var accountId = Long.parseLong(normalizedAcctNum);
        final var feeAccountKeyFile = keyFileFor(config.keysLoc(), "account" + accountId);
        final var maybeFeeAccountKeyPath = feeAccountKeyFile.map(File::getPath).orElse(null);
        if (maybeFeeAccountKeyPath == null) {
            COMMON_MESSAGES.warn("No key on disk for account " + accountId
                    + ", payer and admin key signatures must meet its signing requirements");
        }

        final var gossipCert = validatedX509Cert(
                gossipCaCertificatePath, gossipCaCertificatePfxPath, gossipCaCertificatePfxAlias, yahcli);
        // Throws if the cert is not valid
        validatedX509Cert(hapiCertificatePath, null, null, yahcli);
        final boolean parsedDeclineRewards = declineRewards == null || declineRewards;
        final var delegate = new CreateNodeSuite(
                config,
                accountId,
                Optional.ofNullable(description).orElse(""),
                asCsServiceEndpoints(gossipEndpoints),
                asCsServiceEndpoints(serviceEndpoints),
                gossipCert,
                noThrowSha384HashOf(allBytesAt(Paths.get(hapiCertificatePath))),
                adminKeyPath,
                maybeFeeAccountKeyPath,
                parsedDeclineRewards,
                grpcProxyEndpoint == null ? null : asTypedServiceEndpoint(grpcProxyEndpoint));
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            COMMON_MESSAGES.info("SUCCESS - created node" + delegate.createdIdOrThrow());
        } else {
            COMMON_MESSAGES.warn("FAILED to create node");
            return 1;
        }

        return 0;
    }

    static byte[] allBytesAt(@NonNull final Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void validateAdminKeyLoc(@NonNull final String adminKeyPath) {
        final Optional<File> adminKeyFile;
        try {
            adminKeyFile = AccessoryUtils.keyFileAt(adminKeyPath.substring(0, adminKeyPath.lastIndexOf('.')));
        } catch (Exception e) {
            throw new CommandLine.ParameterException(
                    nodesCommand.getYahcli().getSpec().commandLine(),
                    "Could not load a key from '" + adminKeyPath + "' (" + e.getMessage() + ")");
        }
        if (adminKeyFile.isEmpty()) {
            throw new CommandLine.ParameterException(
                    nodesCommand.getYahcli().getSpec().commandLine(),
                    "Could not load a key from '" + adminKeyPath + "'");
        }
    }

    private AccountID validatedAccountId(@NonNull final String accountNum) {
        try {
            return AccountID.newBuilder()
                    .setAccountNum(Long.parseLong(accountNum))
                    .build();
        } catch (NumberFormatException e) {
            throw new CommandLine.ParameterException(
                    nodesCommand.getYahcli().getSpec().commandLine(), "Invalid account number '" + accountNum + "'");
        }
    }
}
