// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.validation;

import com.hedera.node.config.types.KeyValuePair;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;

@ConfigData
public record BadDefinedRecord(
        @EmulatesMap @ConfigProperty KeyValuePair pair, @EmulatesMap @ConfigProperty List<String> data) {}
