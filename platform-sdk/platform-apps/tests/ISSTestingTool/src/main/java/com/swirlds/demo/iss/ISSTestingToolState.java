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

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import com.swirlds.state.test.fixtures.merkle.singleton.StringLeaf;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
@ConstructableIgnored
public class ISSTestingToolState extends MerkleStateRoot<ISSTestingToolState> implements MerkleNodeState {

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    static {
        registerMerkleStateRootClassIds();
    }

    private static final long CLASS_ID = 0xf059378c7764ef47L;

    // 0 is PLATFORM_STATE, 1 is ROSTERS, 2 is ROSTER_STATE
    private static final int RUNNING_SUM_INDEX = 3;
    private static final int GENESIS_TIMESTAMP_INDEX = 4;
    private static final int PLANNED_ISS_LIST_INDEX = 5;
    private static final int PLANNED_LOG_ERROR_LIST_INDEX = 6;

    /**
     * The true "state" of this app. Each transaction is just an integer that gets added to this value.
     */
    private long runningSum = 0;

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

    public ISSTestingToolState() {
        // no-op
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

        // since the test occurrences are relative to the genesis timestamp, the data only needs to be parsed at genesis
        if (trigger == InitTrigger.GENESIS) {
            final ISSTestingToolConfig testingToolConfig =
                    platform.getContext().getConfiguration().getConfigData(ISSTestingToolConfig.class);

            this.plannedIssList = testingToolConfig.getPlannedISSs();
            this.plannedLogErrorList = testingToolConfig.getPlannedLogErrors();
            writeObjectByChildIndex(PLANNED_ISS_LIST_INDEX, plannedIssList);
            writeObjectByChildIndex(PLANNED_LOG_ERROR_LIST_INDEX, plannedLogErrorList);
        } else {
            final StringLeaf runningSumLeaf = getChild(RUNNING_SUM_INDEX);
            if (runningSumLeaf != null) {
                runningSum = Long.parseLong(runningSumLeaf.getLabel());
            }
            final StringLeaf genesisTimestampLeaf = getChild(GENESIS_TIMESTAMP_INDEX);
            if (genesisTimestampLeaf != null) {
                genesisTimestamp = Instant.parse(genesisTimestampLeaf.getLabel());
            }
            plannedIssList = readObjectByChildIndex(PLANNED_ISS_LIST_INDEX, PlannedIss::new);
            plannedLogErrorList = readObjectByChildIndex(PLANNED_LOG_ERROR_LIST_INDEX, PlannedLogError::new);
        }
    }

    <T extends SelfSerializable> List<T> readObjectByChildIndex(final int index, final Supplier<T> factory) {
        final StringLeaf stringValue = getChild(index);
        if (stringValue != null) {
            try {
                final SerializableDataInputStream in = new SerializableDataInputStream(
                        new ByteArrayInputStream(stringValue.getLabel().getBytes(StandardCharsets.UTF_8)));
                return in.readSerializableList(1024, false, factory);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            return null;
        }
    }

    <T extends SelfSerializable> void writeObjectByChildIndex(final int index, final List<T> list) {
        try {
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final SerializableDataOutputStream out = new SerializableDataOutputStream(byteOut);
            out.writeSerializableList(list, false, true);
            setChild(index, new StringLeaf(byteOut.toString(StandardCharsets.UTF_8)));
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Save the event's timestamp, if needed.
     */
    void captureTimestamp(@NonNull final ConsensusEvent event) {
        if (genesisTimestamp == null) {
            genesisTimestamp = event.getConsensusTimestamp();
            setChild(GENESIS_TIMESTAMP_INDEX, new StringLeaf(genesisTimestamp.toString()));
        }
    }

    void incrementRunningSum(long delta) {
        runningSum += delta;
        setChild(RUNNING_SUM_INDEX, new StringLeaf(Long.toString(runningSum)));
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

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public synchronized ISSTestingToolState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new ISSTestingToolState(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.ORIGINAL;
    }

    @Override
    protected ISSTestingToolState copyingConstructor() {
        return new ISSTestingToolState(this);
    }

    // FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19002
    @Override
    public MerkleNode migrate(@NonNull final Configuration configuration, int version) {
        return this;
    }
}
