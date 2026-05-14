// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.HintsService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V060HintsSchema extends Schema<SemanticVersion> {

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().minor(60).build();

    public static final String CRS_STATE_KEY = "CRS_STATE";
    public static final int CRS_STATE_STATE_ID = SingletonType.HINTSSERVICE_I_CRS_STATE.protoOrdinal();
    public static final String CRS_STATE_STATE_LABEL = computeLabel(HintsService.NAME, CRS_STATE_KEY);

    public static final String CRS_PUBLICATIONS_KEY = "CRS_PUBLICATIONS";
    public static final int CRS_PUBLICATIONS_STATE_ID =
            StateKey.KeyOneOfType.HINTSSERVICE_I_CRS_PUBLICATIONS.protoOrdinal();
    public static final String CRS_PUBLICATIONS_STATE_LABEL = computeLabel(HintsService.NAME, CRS_PUBLICATIONS_KEY);

    public V060HintsSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(CRS_STATE_STATE_ID, CRS_STATE_KEY, CRSState.PROTOBUF),
                StateDefinition.keyValue(
                        CRS_PUBLICATIONS_STATE_ID,
                        CRS_PUBLICATIONS_KEY,
                        NodeId.PROTOBUF,
                        CrsPublicationTransactionBody.PROTOBUF));
    }
}
