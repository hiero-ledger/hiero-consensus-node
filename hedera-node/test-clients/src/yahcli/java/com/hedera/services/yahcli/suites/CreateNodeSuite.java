// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.keyFromFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class CreateNodeSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(CreateNodeSuite.class);

    private final ConfigManager configManager;
    private final long accountId;
    private final String description;
    private final String adminKeyLoc;

    @Nullable
    private final String feeAccountKeyLoc;

    private final boolean declineRewards;

    private final List<ServiceEndpoint> gossipEndpoints;
    private final List<ServiceEndpoint> serviceEndpoints;
    private final byte[] gossipCaCertificate;
    private final byte[] grpcCertificateHash;

    @Nullable
    private Long createdId;

    public CreateNodeSuite(
            @NonNull final ConfigManager configManager,
            final long accountId,
            @NonNull final String description,
            @NonNull final List<ServiceEndpoint> gossipEndpoints,
            @NonNull final List<ServiceEndpoint> serviceEndpoints,
            @NonNull final byte[] gossipCaCertificate,
            @NonNull final byte[] grpcCertificateHash,
            @NonNull final String adminKeyLoc,
            @Nullable final String feeAccountKeyLoc,
            final boolean declineRewards) {
        this.configManager = requireNonNull(configManager);
        this.accountId = accountId;
        this.description = requireNonNull(description);
        this.gossipEndpoints = requireNonNull(gossipEndpoints);
        this.serviceEndpoints = requireNonNull(serviceEndpoints);
        this.gossipCaCertificate = requireNonNull(gossipCaCertificate);
        this.grpcCertificateHash = requireNonNull(grpcCertificateHash);
        this.adminKeyLoc = requireNonNull(adminKeyLoc);
        this.feeAccountKeyLoc = feeAccountKeyLoc;
        this.declineRewards = declineRewards;
    }

    public long createdIdOrThrow() {
        return requireNonNull(createdId);
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(createNode());
    }

    final Stream<DynamicTest> createNode() {
        final var adminKey = "adminKey";
        final var feeAccountKey = "feeAccountKey";
        final var fqAcctId = asAccount(
                configManager.shard().getShardNum(), configManager.realm().getRealmNum(), accountId);

        final var spec =
                new HapiSpec("CreateNode", new MapPropertySource(configManager.asSpecConfig()), new SpecOperation[] {
                    keyFromFile(adminKey, adminKeyLoc).yahcliLogged(),
                    feeAccountKeyLoc == null
                            ? noOp()
                            : keyFromFile(feeAccountKey, feeAccountKeyLoc).yahcliLogged(),
                    nodeCreate("node")
                            .signedBy(availableSigners())
                            .accountId(fqAcctId)
                            .description(description)
                            .gossipEndpoint(fromPbj(gossipEndpoints))
                            .serviceEndpoint(fromPbj(serviceEndpoints))
                            .gossipCaCertificate(gossipCaCertificate)
                            .grpcCertificateHash(grpcCertificateHash)
                            .adminKey(adminKey)
                            .declineReward(declineRewards)
                            .advertisingCreation()
                            .exposingCreatedIdTo(createdId -> this.createdId = createdId)
                });
        return HapiSpecUtils.targeted(spec, configManager);
    }

    private String[] availableSigners() {
        if (feeAccountKeyLoc == null) {
            return new String[] {DEFAULT_PAYER, "adminKey"};
        } else {
            return new String[] {DEFAULT_PAYER, "adminKey", "feeAccountKey"};
        }
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
