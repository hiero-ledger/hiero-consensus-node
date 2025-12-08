// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.inputs;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookId;
import java.util.List;
import java.util.Map;

public record HookMetadata(List<HookId> execHookIds, Map<HookId, List<List<ContractID>>> hookCreations) {}
