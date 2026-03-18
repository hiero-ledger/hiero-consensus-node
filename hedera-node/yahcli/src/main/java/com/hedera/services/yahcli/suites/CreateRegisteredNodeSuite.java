// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.keyFromFile;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class CreateRegisteredNodeSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(CreateRegisteredNodeSuite.class);

    private final ConfigManager configManager;
    private final String adminKeyLoc;
    private final List<RegisteredServiceEndpoint> endpoints;

    @Nullable
    private final String description;

    @Nullable
    private Long createdId;

    public CreateRegisteredNodeSuite(
            @NonNull final ConfigManager configManager,
            @Nullable final String description,
            @NonNull final List<RegisteredServiceEndpoint> endpoints,
            @NonNull final String adminKeyLoc) {
        this.configManager = requireNonNull(configManager);
        this.description = description;
        this.endpoints = requireNonNull(endpoints);
        this.adminKeyLoc = requireNonNull(adminKeyLoc);
    }

    public long createdIdOrThrow() {
        return requireNonNull(createdId);
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(createRegisteredNode());
    }

    final Stream<DynamicTest> createRegisteredNode() {
        final var adminKey = "adminKey";

        var nodeCreate = registeredNodeCreate("node")
                .signedBy(DEFAULT_PAYER, adminKey)
                .adminKey(adminKey)
                .serviceEndpoints(endpoints)
                .advertisingCreation()
                .exposingCreatedIdTo(id -> this.createdId = id);

        if (description != null && !description.isEmpty()) {
            nodeCreate = nodeCreate.description(description);
        }

        final var spec = new HapiSpec(
                "CreateRegisteredNode",
                new MapPropertySource(configManager.asSpecConfig()),
                new SpecOperation[] {keyFromFile(adminKey, adminKeyLoc).yahcliLogged(), nodeCreate});
        return HapiSpecUtils.targeted(spec, configManager);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
