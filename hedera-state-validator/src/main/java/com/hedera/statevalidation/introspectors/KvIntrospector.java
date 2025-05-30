// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.introspectors;

import static com.hedera.statevalidation.introspectors.IntrospectUtils.getCodecFor;

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
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;

public class KvIntrospector {

    private final State state;
    private final String serviceName;
    private final String stateName;
    private final String keyType;
    private final String keyJson;

    public KvIntrospector(State state, String serviceName, String stateName, String keyInfo) {
        this.state = state;
        this.serviceName = serviceName;
        this.stateName = stateName;
        String[] typeAndJson = keyInfo.split(":", 2);
        keyType = typeAndJson[0];
        keyJson = typeAndJson[1];
    }

    public void introspect() {
        ReadableKVState<Object, Object> kvState =
                state.getReadableStates(serviceName).get(stateName);
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
            Object key = jsonCodec.parse(Bytes.wrap(keyJson));
            Object value = kvState.get(key);
            if (value == null) {
                System.out.println("Value not found");
            } else {
                System.out.println(getCodecFor(value).toJSON(value));
            }
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }
}
