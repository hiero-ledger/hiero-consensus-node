// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.keyFromFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.transactions.node.HapiRegisteredNodeUpdate;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class UpdateRegisteredNodeSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(UpdateRegisteredNodeSuite.class);

    private final ConfigManager configManager;
    private final long nodeId;

    @Nullable
    private final String adminKeyLoc;

    @Nullable
    private final String newAdminKeyLoc;

    @Nullable
    private final String description;

    @Nullable
    private final List<RegisteredServiceEndpoint> endpoints;

    public UpdateRegisteredNodeSuite(
            @NonNull final ConfigManager configManager,
            final long nodeId,
            @Nullable final String adminKeyLoc,
            @Nullable final String newAdminKeyLoc,
            @Nullable final String description,
            @Nullable final List<RegisteredServiceEndpoint> endpoints) {
        this.configManager = requireNonNull(configManager);
        this.nodeId = nodeId;
        this.adminKeyLoc = adminKeyLoc;
        this.newAdminKeyLoc = newAdminKeyLoc;
        this.description = description;
        this.endpoints = endpoints;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doUpdate());
    }

    final Stream<DynamicTest> doUpdate() {
        final var adminKey = "adminKey";
        final var newAdminKey = "newAdminKey";
        final var spec =
                new HapiSpec("UpdateRegisteredNode", new MapPropertySource(configManager.asSpecConfig()), new SpecOperation[] {
                    adminKeyLoc == null
                            ? noOp()
                            : keyFromFile(adminKey, adminKeyLoc).yahcliLogged(),
                    newAdminKeyLoc == null
                            ? noOp()
                            : keyFromFile(newAdminKey, newAdminKeyLoc).yahcliLogged(),
                    updateOp()
                });
        return HapiSpecUtils.targeted(spec, configManager);
    }

    private HapiRegisteredNodeUpdate updateOp() {
        final var op = registeredNodeUpdate(() -> nodeId);
        if (description != null) {
            op.description(description);
        }
        if (endpoints != null) {
            op.serviceEndpoints(endpoints);
        }
        if (newAdminKeyLoc != null) {
            op.adminKey("newAdminKey");
        }
        return op.signedBy(availableSigners());
    }

    private String[] availableSigners() {
        final List<String> signers = new ArrayList<>();
        signers.add(DEFAULT_PAYER);
        if (adminKeyLoc != null) {
            signers.add("adminKey");
        }
        if (newAdminKeyLoc != null) {
            signers.add("newAdminKey");
        }
        return signers.toArray(String[]::new);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
