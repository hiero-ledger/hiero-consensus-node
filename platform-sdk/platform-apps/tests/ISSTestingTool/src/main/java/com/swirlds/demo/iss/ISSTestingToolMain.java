/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.demo.iss;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.test.fixtures.state.FakeStateLifecycles.FAKE_MERKLE_STATE_LIFECYCLES;
import static com.swirlds.platform.test.fixtures.state.FakeStateLifecycles.registerMerkleStateRootClassIds;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.state.StateLifecycles;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.system.state.notifications.IssListener;
import com.swirlds.platform.system.state.notifications.IssNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An application that can be made to ISS in controllable ways.
 * <p>
 * A log error can also be scheduled to be written. This is useful because it's' possible that not all nodes learn
 * about an ISS, since nodes stop gossiping when they detect the ISS. Slow nodes may not detect the ISS before their
 * peers stop gossiping. Therefore, we can validate that a scheduled log error doesn't occur, due to consensus coming to
 * a halt, even if an ISS isn't detected.
 */
public class ISSTestingToolMain implements SwirldMain<ISSTestingToolState> {

    private static final Logger logger = LogManager.getLogger(ISSTestingToolMain.class);

    private static final BasicSoftwareVersion softwareVersion = new BasicSoftwareVersion(1);

    static {
        try {
            logger.info(STARTUP.getMarker(), "Registering ISSTestingToolState with ConstructableRegistry");
            ConstructableRegistry constructableRegistry = ConstructableRegistry.getInstance();
            constructableRegistry.registerConstructable(new ClassConstructorPair(ISSTestingToolState.class, () -> {
                ISSTestingToolState issTestingToolState = new ISSTestingToolState();
                return issTestingToolState;
            }));
            registerMerkleStateRootClassIds();
            logger.info(STARTUP.getMarker(), "ISSTestingToolState is registered with ConstructableRegistry");
        } catch (ConstructableRegistryException e) {
            logger.error(STARTUP.getMarker(), "Failed to register ISSTestingToolState", e);
            throw new RuntimeException(e);
        }
    }

    private Platform platform;

    /**
     * Constructor
     */
    public ISSTestingToolMain() {
        logger.info(STARTUP.getMarker(), "constructor called in Main.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(final Platform platform, final NodeId id) {
        this.platform = platform;

        platform.getNotificationEngine().register(IssListener.class, this::issListener);
    }

    /**
     * Called when there is an ISS.
     */
    private void issListener(final IssNotification notification) {
        // Quan: this is a good place to write logs that the validators catch
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        final ISSTestingToolConfig testingToolConfig =
                platform.getContext().getConfiguration().getConfigData(ISSTestingToolConfig.class);

        new TransactionGenerator(new Random(), platform, testingToolConfig.transactionsPerSecond()).start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ISSTestingToolState newStateRoot() {
        final ISSTestingToolState state = new ISSTestingToolState();
        FAKE_MERKLE_STATE_LIFECYCLES.initStates(state);
        return state;
    }

    @Override
    public StateLifecycles<ISSTestingToolState> newStateLifecycles() {
        return new ISSTestingToolStateLifecycles();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BasicSoftwareVersion getSoftwareVersion() {
        return softwareVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public List<Class<? extends Record>> getConfigDataTypes() {
        return List.of(ISSTestingToolConfig.class);
    }

    @Override
    public Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction transaction) {
        return StateSignatureTransaction.PROTOBUF.toBytes(transaction);
    }
}
