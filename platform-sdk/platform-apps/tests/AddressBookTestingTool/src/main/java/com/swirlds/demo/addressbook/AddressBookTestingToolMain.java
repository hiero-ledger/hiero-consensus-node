// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.addressbook;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.FAKE_CONSENSUS_STATE_EVENT_HANDLER;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.registerMerkleStateRootClassIds;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * <p>
 * An application that updates address book weights on version upgrade.
 * </p>
 *
 * <p>
 * Arguments:
 * <ol>
 * <li>
 * No arguments parsed at this time.  The software version must be updated through setting the static value in
 * this main class and recompiling. The behavior of weighting is updated in the State class and recompiling.
 * </li>
 * </ol>
 */
public class AddressBookTestingToolMain implements SwirldMain<AddressBookTestingToolState> {

    /** The logger for this class. */
    private static final Logger logger = LogManager.getLogger(AddressBookTestingToolMain.class);

    static {
        try {
            logger.info(STARTUP.getMarker(), "Registering AddressBookTestingToolState with ConstructableRegistry");
            ConstructableRegistry constructableRegistry = ConstructableRegistry.getInstance();
            constructableRegistry.registerConstructable(
                    new ClassConstructorPair(AddressBookTestingToolState.class, AddressBookTestingToolState::new));
            registerMerkleStateRootClassIds();
            logger.info(STARTUP.getMarker(), "AddressBookTestingToolState is registered with ConstructableRegistry");
        } catch (ConstructableRegistryException e) {
            logger.error(STARTUP.getMarker(), "Failed to register AddressBookTestingToolState", e);
            throw new RuntimeException(e);
        }
    }

    /** The software version of this application. */
    private BasicSoftwareVersion softwareVersion;

    /** The semantic version of this application. */
    private SemanticVersion semanticVersion;

    /** The platform. */
    private Platform platform;

    /** The number of transactions to generate per second. */
    private static final int TRANSACTIONS_PER_SECOND = 1000;

    public AddressBookTestingToolMain() {
        logger.info(STARTUP.getMarker(), "constructor called in Main.");
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public List<Class<? extends Record>> getConfigDataTypes() {
        return List.of(AddressBookTestingToolConfig.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(@NonNull final Platform platform, @NonNull final NodeId id) {
        Objects.requireNonNull(platform, "The platform must not be null.");
        Objects.requireNonNull(id, "The node id must not be null.");

        logger.info(STARTUP.getMarker(), "init called in Main for node {}.", id);
        this.platform = platform;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        logger.info(STARTUP.getMarker(), "run called in Main.");
        new TransactionGenerator(new SecureRandom(), platform, TRANSACTIONS_PER_SECOND).start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AddressBookTestingToolState newStateRoot() {
        final AddressBookTestingToolState state = new AddressBookTestingToolState();
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initStates(state);
        return state;
    }

    @Override
    @NonNull
    public ConsensusStateEventHandler<AddressBookTestingToolState> newConsensusStateEvenHandler() {
        return new AddressBookTestingToolConsensusStateEventHandler(new PlatformStateFacade());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public BasicSoftwareVersion getSoftwareVersion() {
        if (softwareVersion != null) {
            return softwareVersion;
        }

        // Preload configuration so that we can change the software version on the fly
        final Configuration configuration;
        try {
            final ConfigurationBuilder configurationBuilder =
                    ConfigurationBuilder.create().withConfigDataType(AddressBookTestingToolConfig.class);
            configuration = DefaultConfiguration.buildBasicConfiguration(
                    configurationBuilder, getAbsolutePath("settings.txt"), List.of());
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to load settings.txt", e);
        }

        final int version =
                configuration.getConfigData(AddressBookTestingToolConfig.class).softwareVersion();
        this.softwareVersion = new BasicSoftwareVersion(version);

        logger.info(STARTUP.getMarker(), "returning software version {}", softwareVersion);
        return softwareVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SemanticVersion getSemanticVersion() {
        if (semanticVersion != null) {
            return semanticVersion;
        }

        // Preload configuration so that we can change the software version on the fly
        final Configuration configuration;
        try {
            final ConfigurationBuilder configurationBuilder =
                    ConfigurationBuilder.create().withConfigDataType(AddressBookTestingToolConfig.class);
            configuration = DefaultConfiguration.buildBasicConfiguration(
                    configurationBuilder, getAbsolutePath("settings.txt"), List.of());
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to load settings.txt", e);
        }

        final int version =
                configuration.getConfigData(AddressBookTestingToolConfig.class).softwareVersion();
        this.semanticVersion = SemanticVersion.newBuilder().major(version).build();

        logger.info(STARTUP.getMarker(), "returning semantic version {}", semanticVersion);
        return semanticVersion;
    }

    @Override
    public Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction transaction) {
        return StateSignatureTransaction.PROTOBUF.toBytes(transaction);
    }
}
