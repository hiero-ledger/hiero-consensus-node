// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_DELETE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_GET_CONTENTS;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_UPDATE;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hiero.hapi.fees.apis.common.BaseFeeModel;

public class FeeModelRegistry {
    private static final Map<HederaFunctionality, FeeModel> registry = new LinkedHashMap<>();

    static {
        registry.put(CONSENSUS_CREATE_TOPIC, new BaseFeeModel(CONSENSUS_CREATE_TOPIC, "Create a new topic"));
        registry.put(CONSENSUS_UPDATE_TOPIC, new BaseFeeModel(CONSENSUS_UPDATE_TOPIC, "Update topic"));
        registry.put(CONSENSUS_SUBMIT_MESSAGE, new BaseFeeModel(CONSENSUS_SUBMIT_MESSAGE, "Submit message to topic"));
        registry.put(CONSENSUS_DELETE_TOPIC, new BaseFeeModel(CONSENSUS_DELETE_TOPIC, "Delete topic"));
        registry.put(FILE_CREATE, new BaseFeeModel(FILE_CREATE, "Create file"));
        registry.put(FILE_UPDATE, new BaseFeeModel(FILE_UPDATE, "Update file"));
        registry.put(FILE_APPEND, new BaseFeeModel(FILE_APPEND, "Append to file"));
        registry.put(FILE_DELETE, new BaseFeeModel(FILE_DELETE, "Delete file"));
        registry.put(FILE_GET_CONTENTS, new BaseFeeModel(FILE_GET_CONTENTS, "Get file contents"));
        registry.put(FILE_GET_INFO, new BaseFeeModel(FILE_GET_INFO, "Get file info"));
    }

    public static FeeModel lookupModel(HederaFunctionality service) {
        if (!registry.containsKey(service)) {
            throw new IllegalArgumentException("No registered model found for service " + service);
        }
        return registry.get(service);
    }
}
