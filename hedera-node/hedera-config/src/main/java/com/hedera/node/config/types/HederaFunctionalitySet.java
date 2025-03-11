// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.util.Set;

public record HederaFunctionalitySet(Set<HederaFunctionality> functionalitySet) {}
