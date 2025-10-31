// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.introspector;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.history.ConstructionNodeId;
import com.hedera.hapi.node.state.tss.TssMessageMapKey;
import com.hedera.hapi.node.state.tss.TssVoteMapKey;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.pbj.runtime.JsonCodec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;

public class KvIntrospector {

    private final State state;
    private final String serviceName;
    private final int stateId;
    private final String keyType;
    private final String keyJson;

    public KvIntrospector(
            @NonNull final State state, @NonNull final String serviceName, int stateId, @NonNull final String keyInfo) {
        this.state = state;
        this.serviceName = serviceName;
        this.stateId = stateId;
        final String[] typeAndJson = keyInfo.split(":", 2);
        this.keyType = typeAndJson[0];
        this.keyJson = typeAndJson[1];
    }

    @SuppressWarnings({"deprecation", "rawtypes"})
    public void introspect() {
        final ReadableKVState<Object, Object> kvState =
                state.getReadableStates(serviceName).get(stateId);
        final JsonCodec jsonCodec;
        switch (keyType) {
            case "EntityNumber" -> jsonCodec = EntityNumber.JSON;
            case "TopicID" -> jsonCodec = TopicID.JSON;
            case "SlotKey" -> jsonCodec = SlotKey.JSON;
            case "ContractID" -> jsonCodec = ContractID.JSON;
            case "FileID" -> jsonCodec = FileID.JSON;
            case "ScheduleID" -> jsonCodec = ScheduleID.JSON;
            case "TokenID" -> jsonCodec = TokenID.JSON;
            case "AccountID" -> jsonCodec = AccountID.JSON;
            case "NftID" -> jsonCodec = NftID.JSON;
            case "EntityIDPair" -> jsonCodec = EntityIDPair.JSON;
            case "PendingAirdropId" -> jsonCodec = PendingAirdropId.JSON;
            case "TssVoteMapKey" -> jsonCodec = TssVoteMapKey.JSON;
            case "TssMessageMapKey" -> jsonCodec = TssMessageMapKey.JSON;
            case "NodeId" -> jsonCodec = NodeId.JSON;
            case "ConstructionNodeId" -> jsonCodec = ConstructionNodeId.JSON;
            case "HintsPartyId" -> jsonCodec = HintsPartyId.JSON;
            default -> throw new UnsupportedOperationException("Key type not supported: " + keyType);
        }
        try {
            final Object key = jsonCodec.parse(Bytes.wrap(keyJson));
            final Object value = kvState.get(key);
            if (value == null) {
                System.out.println("Value not found");
            } else {
                //noinspection unchecked
                System.out.println(StateUtils.getCodecFor(value).toJSON(value));
            }
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }
}
