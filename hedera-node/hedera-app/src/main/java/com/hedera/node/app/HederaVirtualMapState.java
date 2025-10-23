// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.platform.state.QueueState;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.utility.Mnemonics;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.merkle.StateKeyUtils;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.constructable.ConstructableIgnored;
import org.json.JSONObject;

/**
 * This class sole purpose is to extend the {@link VirtualMapState} class and implement the {@link MerkleNodeState}.
 * Technically, {@link VirtualMapState} is already implementing {@link State} and {@link MerkleNode} but it does not
 * implement the {@link MerkleNodeState} interface. This class is merely a connector of these two interfaces.
 */
@ConstructableIgnored
public class HederaVirtualMapState extends VirtualMapState<HederaVirtualMapState> implements MerkleNodeState {

    /**
     * Constructs a {@link HederaVirtualMapState}.
     *
     * @param configuration the platform configuration instance to use when creating the new instance of state
     * @param metrics       the platform metric instance to use when creating the new instance of state
     * @param time          the time instance to use when creating the new instance of state
     */
    public HederaVirtualMapState(
            @NonNull final Configuration configuration, @NonNull final Metrics metrics, @NonNull final Time time) {
        super(configuration, metrics, time);
    }

    /**
     * Constructs a {@link HederaVirtualMapState} using the specified {@link VirtualMap}.
     *
     * @param virtualMap the virtual map whose metrics must already be registered
     * @param metrics    the platform metric instance to use when creating the new instance of state
     * @param time       the time instance to use when creating the new instance of state
     */
    public HederaVirtualMapState(
            @NonNull final VirtualMap virtualMap, @NonNull final Metrics metrics, @NonNull final Time time) {
        super(virtualMap, metrics, time);
    }

    protected HederaVirtualMapState(@NonNull final HederaVirtualMapState from) {
        super(from);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected HederaVirtualMapState copyingConstructor() {
        return new HederaVirtualMapState(this);
    }

    /**
     * Creates a new instance of {@link HederaVirtualMapState} with the specified {@link VirtualMap}.
     *
     * @param virtualMap the virtual map whose metrics must already be registered
     * @param metrics    the platform metric instance to use when creating the new instance of state
     * @param time       the time instance to use when creating the new instance of state
     * @return a new instance of {@link HederaVirtualMapState}
     */
    @Override
    protected HederaVirtualMapState newInstance(
            @NonNull final VirtualMap virtualMap, @NonNull final Metrics metrics, @NonNull final Time time) {
        return new HederaVirtualMapState(virtualMap, metrics, time);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRound() {
        return DEFAULT_PLATFORM_STATE_FACADE.roundOf(this);
    }

    @Override
    public String getInfoJson() {
        final JSONObject rootJson = new JSONObject();

        final RecordAccessor recordAccessor = virtualMap.getRecords();
        final VirtualMapMetadata virtualMapMetadata = virtualMap.getMetadata();

        final JSONObject virtualMapMetadataJson = new JSONObject();
        virtualMapMetadataJson.put("firstLeafPath", virtualMapMetadata.getFirstLeafPath());
        virtualMapMetadataJson.put("lastLeafPath", virtualMapMetadata.getLastLeafPath());

        rootJson.put("VirtualMapMetadata", virtualMapMetadataJson);

        final JSONObject singletons = new JSONObject();
        final JSONObject queues = new JSONObject();

        services.forEach((key, value) -> {
            value.forEach((s, stateMetadata) -> {
                final String serviceName = stateMetadata.serviceName();
                final StateDefinition<?, ?> stateDefinition = stateMetadata.stateDefinition();
                final int stateId = stateDefinition.stateId();
                final String stateKey = stateDefinition.stateKey();

                if (stateDefinition.singleton()) {
                    final Bytes singletonKey = StateKeyUtils.singletonKey(stateId);
                    final VirtualLeafBytes<?> leafBytes = recordAccessor.findLeafRecord(singletonKey);
                    if (leafBytes != null) {
                        final var hash = recordAccessor.findHash(leafBytes.path());
                        final JSONObject singletonJson = new JSONObject();
                        if (hash != null) {
                            singletonJson.put("mnemonic", Mnemonics.generateMnemonic(hash));
                        }
                        singletonJson.put("path", leafBytes.path());
                        singletons.put(computeLabel(serviceName, stateKey), singletonJson);
                    }
                } else if (stateDefinition.queue()) {
                    final Bytes queueStateKey = StateKeyUtils.queueStateKey(stateId);
                    final VirtualLeafBytes<?> leafBytes = recordAccessor.findLeafRecord(queueStateKey);
                    if (leafBytes != null) {
                        try {
                            final StateValue stateValue = StateValue.PROTOBUF.parse(leafBytes.valueBytes());
                            final QueueState queueState = stateValue.queueState();
                            final JSONObject queueJson = new JSONObject();
                            queueJson.put("head", queueState.head());
                            queueJson.put("tail", queueState.tail());
                            queueJson.put("path", leafBytes.path());
                            queues.put(computeLabel(serviceName, stateKey), queueJson);
                        } catch (ParseException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });
        });

        rootJson.put("Singletons", singletons);
        rootJson.put("Queues (Queue States)", queues);

        return rootJson.toString();
    }
}
