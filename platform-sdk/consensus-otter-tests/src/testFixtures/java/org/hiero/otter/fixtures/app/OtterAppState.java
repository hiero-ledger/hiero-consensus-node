// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.roster.RosterUtils;

public class OtterAppState extends MerkleStateRoot<OtterAppState> implements MerkleNodeState {

    private static final long CLASS_ID = 0x107F552E92071390L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    /**
     * Creates an initialized {@code TurtleAppState}.
     *
     * @param configuration the configuration used during initialization
     * @param roster the initial roster stored in the state
     * @param version the software version to set in the state
     * @return merkle tree root
     */
    @NonNull
    public static OtterAppState createGenesisState(
            @NonNull final Configuration configuration,
            @NonNull final Roster roster,
            @NonNull final SemanticVersion version) {
        final TestingAppStateInitializer initializer = new TestingAppStateInitializer(configuration);
        final OtterAppState state = new OtterAppState();
        initializer.initStates(state);
        RosterUtils.setActiveRoster(state, roster, 0L);
        DEFAULT_PLATFORM_STATE_FACADE.setCreationSoftwareVersionTo(state, version);
        return state;
    }

    long state;

    public OtterAppState() {
        // empty
    }

    /**
     * Copy constructor.
     *
     * @param from the object to copy
     */
    private OtterAppState(@NonNull final OtterAppState from) {
        super(from);
        this.state = from.state;
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

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OtterAppState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new OtterAppState(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected OtterAppState copyingConstructor() {
        return new OtterAppState(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.ORIGINAL;
    }
}
