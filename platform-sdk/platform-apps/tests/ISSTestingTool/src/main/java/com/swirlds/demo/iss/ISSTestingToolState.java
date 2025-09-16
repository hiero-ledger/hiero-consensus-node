// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;
/*
 * This file is public domain.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

import static com.swirlds.demo.iss.V0660ISSTestingToolSchema.GENESIS_TIMESTAMP_STATE_ID;
import static com.swirlds.demo.iss.V0660ISSTestingToolSchema.ISS_SERVICE_NAME;
import static com.swirlds.demo.iss.V0660ISSTestingToolSchema.PLANNED_ISS_LIST_STATE_ID;
import static com.swirlds.demo.iss.V0660ISSTestingToolSchema.PLANNED_LOG_ERROR_LIST_STATE_ID;
import static com.swirlds.demo.iss.V0660ISSTestingToolSchema.RUNNING_SUM_STATE_ID;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.disk.OnDiskReadableSingletonState;
import com.swirlds.state.merkle.disk.OnDiskWritableSingletonState;
import com.swirlds.state.merkle.singleton.StringLeaf;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.event.ConsensusEvent;

/**
 * State for the ISSTestingTool.
 */
public class ISSTestingToolState extends VirtualMapState<ISSTestingToolState> implements MerkleNodeState {

    static {
        registerMerkleStateRootClassIds();
    }

    /**
     * The true "state" of this app. Each transaction is just an integer that gets added to this value.
     */
    private long runningSum;

    /**
     * The timestamp of the first event after genesis.
     */
    private Instant genesisTimestamp;

    /**
     * A list of ISS incidents that will be triggered at a predetermined consensus time
     */
    private List<PlannedIss> plannedIssList;

    /**
     * A list of errors that will be logged at a predetermined consensus time
     */
    private List<PlannedLogError> plannedLogErrorList;

    public ISSTestingToolState(@NonNull final VirtualMap virtualMap) {
        super(virtualMap);
    }

    /**
     * Copy constructor.
     */
    private ISSTestingToolState(final ISSTestingToolState that) {
        super(that);
        this.runningSum = that.runningSum;
        this.genesisTimestamp = that.genesisTimestamp;
        this.plannedIssList = new ArrayList<>(that.plannedIssList);
        this.plannedLogErrorList = new ArrayList<>(that.plannedLogErrorList);
    }

    @Override
    protected ISSTestingToolState copyingConstructor() {
        return new ISSTestingToolState(this);
    }

    @Override
    protected ISSTestingToolState newInstance(@NonNull final VirtualMap virtualMap) {
        return new ISSTestingToolState(virtualMap);
    }

    public void initState(InitTrigger trigger, Platform platform) {
        throwIfImmutable();

        final PlatformContext platformContext = platform.getContext();
        super.init(
                platformContext.getTime(),
                platformContext.getConfiguration(),
                platformContext.getMetrics(),
                platformContext.getMerkleCryptography(),
                () -> DEFAULT_PLATFORM_STATE_FACADE.roundOf(this));

        final var schema = new V0660ISSTestingToolSchema();
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateId))
                .forEach(def -> {
                    super.initializeState(new StateMetadata<>(ISS_SERVICE_NAME, schema, def));
                });
        // do something with migration context?

        // since the test occurrences are relative to the genesis timestamp, the data only needs to be parsed at genesis
        if (trigger == InitTrigger.GENESIS) {
            final ISSTestingToolConfig testingToolConfig =
                    platform.getContext().getConfiguration().getConfigData(ISSTestingToolConfig.class);

            this.plannedIssList = testingToolConfig.getPlannedISSs();
            this.plannedLogErrorList = testingToolConfig.getPlannedLogErrors();

            final WritableStates writableStates = getWritableStates(ISS_SERVICE_NAME);

            final WritableQueueState<PlannedIss> plannedIssState = writableStates.getQueue(PLANNED_ISS_LIST_STATE_ID);
            plannedIssList.forEach(plannedIssState::add);
            ((CommittableWritableStates) plannedIssState).commit();

            final WritableQueueState<PlannedLogError> plannedLogErrorState = writableStates.getQueue(PLANNED_LOG_ERROR_LIST_STATE_ID);
            plannedLogErrorList.forEach(plannedLogErrorState::add);
            ((CommittableWritableStates) plannedLogErrorState).commit();
        } else {
            final ReadableStates readableStates = getReadableStates(ISS_SERVICE_NAME);

            final ReadableSingletonState<Long> runningSumState = readableStates.getSingleton(RUNNING_SUM_STATE_ID);
            final Long runningSum = runningSumState.get();
            if (runningSum != null) {
                this.runningSum = runningSum;
            }

            final ReadableSingletonState<String> genesisTimestampState = readableStates.getSingleton(GENESIS_TIMESTAMP_STATE_ID);
            final String genesisTimestampString = genesisTimestampState.get();
            if (genesisTimestampString != null) {
                this.genesisTimestamp = Instant.parse(genesisTimestampString);
            }

            final ReadableQueueState<PlannedIss> plannedIssState = readableStates.getQueue(PLANNED_ISS_LIST_STATE_ID);
            plannedIssState.iterator().forEachRemaining(plannedIss -> this.plannedIssList.add(plannedIss));

            final ReadableQueueState<PlannedLogError> plannedLogErrorState = readableStates.getQueue(PLANNED_LOG_ERROR_LIST_STATE_ID);
            plannedLogErrorState.iterator().forEachRemaining(plannedLogError -> this.plannedLogErrorList.add(plannedLogError));
        }
    }

    /**
     * Save the event's timestamp, if needed.
     */
    void captureTimestamp(@NonNull final ConsensusEvent event) {
        if (genesisTimestamp == null) {
            genesisTimestamp = event.getConsensusTimestamp();
            getWritableStates(ISS_SERVICE_NAME).getSingleton(GENESIS_TIMESTAMP_STATE_ID).put(genesisTimestamp.toString());
        }
    }

    void incrementRunningSum(long delta) {
        runningSum += delta;
        getWritableStates(ISS_SERVICE_NAME).getSingleton(RUNNING_SUM_STATE_ID).put(runningSum);
    }

    Instant getGenesisTimestamp() {
        return genesisTimestamp;
    }

    List<PlannedIss> getPlannedIssList() {
        return plannedIssList;
    }

    List<PlannedLogError> getPlannedLogErrorList() {
        return plannedLogErrorList;
    }
}
