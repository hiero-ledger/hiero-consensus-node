package com.swirlds.platform.test.consensus;

import static com.swirlds.config.api.ConfigProperty.NULL_DEFAULT_VALUE;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;



@ConfigData("consensusTest")
public record ConsensusTestConfig(@ConfigProperty(defaultValue = NULL_DEFAULT_VALUE) SemanticVersion firstVersionInBirthRoundMode) {
}
