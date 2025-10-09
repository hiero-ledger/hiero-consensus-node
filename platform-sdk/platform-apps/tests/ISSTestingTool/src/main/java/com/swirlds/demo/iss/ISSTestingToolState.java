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

import static com.swirlds.demo.iss.V0680ISSTestingToolSchema.GENESIS_TIMESTAMP_STATE_ID;
import static com.swirlds.demo.iss.V0680ISSTestingToolSchema.ISS_SERVICE_NAME;
import static com.swirlds.demo.iss.V0680ISSTestingToolSchema.PLANNED_ISS_LIST_STATE_ID;
import static com.swirlds.demo.iss.V0680ISSTestingToolSchema.PLANNED_LOG_ERROR_LIST_STATE_ID;
import static com.swirlds.demo.iss.V0680ISSTestingToolSchema.RUNNING_SUM_STATE_ID;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.disk.OnDiskWritableSingletonState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import org.hiero.consensus.model.event.ConsensusEvent;

/**
 * State for the ISSTestingTool.
 */
public class ISSTestingToolState extends VirtualMapState<ISSTestingToolState> implements MerkleNodeState {

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
    private List<PlannedIss> plannedIssList = new LinkedList<>();

    /**
     * A list of errors that will be logged at a predetermined consensus time
     */
    private List<PlannedLogError> plannedLogErrorList = new LinkedList<>();

    public ISSTestingToolState(@NonNull Configuration configuration, @NonNull Metrics metrics) {
        super(configuration, metrics);
    }

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
                platformContext.getMetrics(),
                platformContext.getMerkleCryptography(),
                () -> DEFAULT_PLATFORM_STATE_FACADE.roundOf(this));

        final var schema = new V0680ISSTestingToolSchema();
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateId))
                .forEach(def -> {
                    super.initializeState(new StateMetadata<>(ISS_SERVICE_NAME, def));
                });

        // since the test occurrences are relative to the genesis timestamp, the data only needs to be parsed at genesis
        if (trigger == InitTrigger.GENESIS) {
            final ISSTestingToolConfig testingToolConfig =
                    platform.getContext().getConfiguration().getConfigData(ISSTestingToolConfig.class);

            this.plannedIssList = testingToolConfig.getPlannedISSs();
            this.plannedLogErrorList = testingToolConfig.getPlannedLogErrors();

            final WritableStates writableStates = getWritableStates(ISS_SERVICE_NAME);

            final WritableQueueState<PlannedIss> plannedIssState = writableStates.getQueue(PLANNED_ISS_LIST_STATE_ID);
            plannedIssList.forEach(plannedIssState::add);

            final WritableQueueState<PlannedLogError> plannedLogErrorState =
                    writableStates.getQueue(PLANNED_LOG_ERROR_LIST_STATE_ID);
            plannedLogErrorList.forEach(plannedLogErrorState::add);

            ((CommittableWritableStates) writableStates).commit();
        } else {
            final ReadableStates readableStates = getReadableStates(ISS_SERVICE_NAME);

            final ReadableSingletonState<ProtoLong> runningSumState = readableStates.getSingleton(RUNNING_SUM_STATE_ID);
            final ProtoLong runningSum = runningSumState.get();
            if (runningSum != null) {
                this.runningSum = runningSum.value();
            }

            final ReadableSingletonState<ProtoString> genesisTimestampState =
                    readableStates.getSingleton(GENESIS_TIMESTAMP_STATE_ID);
            final ProtoString genesisTimestampString = genesisTimestampState.get();
            if (genesisTimestampString != null) {
                this.genesisTimestamp = Instant.parse(genesisTimestampString.value());
            }

            final ReadableQueueState<PlannedIss> plannedIssState = readableStates.getQueue(PLANNED_ISS_LIST_STATE_ID);
            plannedIssState.iterator().forEachRemaining(plannedIss -> this.plannedIssList.add(plannedIss));

            final ReadableQueueState<PlannedLogError> plannedLogErrorState =
                    readableStates.getQueue(PLANNED_LOG_ERROR_LIST_STATE_ID);
            plannedLogErrorState
                    .iterator()
                    .forEachRemaining(plannedLogError -> this.plannedLogErrorList.add(plannedLogError));
        }
    }

    /**
     * Save the event's timestamp, if needed.
     */
    void captureTimestamp(@NonNull final ConsensusEvent event) {
        if (genesisTimestamp == null) {
            genesisTimestamp = event.getConsensusTimestamp();
            final WritableSingletonState<ProtoString> genesisTimestampState =
                    getWritableStates(ISS_SERVICE_NAME).getSingleton(GENESIS_TIMESTAMP_STATE_ID);
            genesisTimestampState.put(new ProtoString(genesisTimestamp.toString()));
            ((OnDiskWritableSingletonState<ProtoString>) genesisTimestampState).commit();
        }
    }

    void incrementRunningSum(long delta) {
        runningSum += delta;
        final WritableSingletonState<ProtoLong> runningSumState =
                getWritableStates(ISS_SERVICE_NAME).getSingleton(RUNNING_SUM_STATE_ID);
        runningSumState.put(new ProtoLong(runningSum));
        ((OnDiskWritableSingletonState<ProtoLong>) runningSumState).commit();
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
