// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

public record PendingCreationMetadata(
        @NonNull ContractOperationStreamBuilder streamBuilder, boolean needsInitcodeExternalized) {}
