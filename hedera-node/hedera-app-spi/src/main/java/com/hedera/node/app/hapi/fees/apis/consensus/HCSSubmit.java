package com.hedera.node.app.hapi.fees.apis.consensus;

import com.hedera.node.app.hapi.fees.AbstractFeeModel;
import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.ParameterDefinition;
import com.hedera.node.app.hapi.fees.apis.common.YesOrNo;

import java.util.List;
import java.util.Map;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.*;

public class HCSSubmit extends AbstractFeeModel {

    private final List<ParameterDefinition> params = List.of(
            new ParameterDefinition("hasCustomFee", "list", new String[] { "Yes", "No" }, "No", 0, 0, "Does this topic have custom fee"),
            new ParameterDefinition("numBytes", "number", null, HCS_FREE_BYTES, HCS_MIN_BYTES, HCS_MAX_BYTES, "Size of the message (bytes)")
    );

    @Override
    public String getService() {
        return "Consensus";
    }

    @Override
    public String getDescription() {
        return "Submit a message to an existing topic";
    }

    @Override
    protected List<ParameterDefinition> apiSpecificParams() {
        return params;
    }

    @Override
    protected FeeResult computeApiSpecificFee(Map<String, Object> values) {
        FeeResult fee = new FeeResult();

        YesOrNo hasCustomFee = (YesOrNo) values.get("hasCustomFee");
        if (hasCustomFee == YesOrNo.NO) {
            fee.addDetail("Base fee", 1, BaseFeeRegistry.getBaseFee("ConsensusSubmitMessage"));
        } else {
            fee.addDetail("Base fee", 1, BaseFeeRegistry.getBaseFee("ConsensusSubmitMessageWithCustomFee"));
        }
        int numBytes = (int) values.get("numBytes");
        if (numBytes > HCS_FREE_BYTES) {
            fee.addDetail("Additional message size", (numBytes - HCS_FREE_BYTES), (numBytes - HCS_FREE_BYTES) * BaseFeeRegistry.getBaseFee("PerHCSByte"));
        }
        return fee;
    }
}
