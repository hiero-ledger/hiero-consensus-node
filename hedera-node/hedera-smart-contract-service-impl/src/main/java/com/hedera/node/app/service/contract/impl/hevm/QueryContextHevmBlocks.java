// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.ethHashFrom;

import com.hedera.node.app.service.contract.impl.annotations.QueryScope;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * A {@link HederaEvmBlocks} implementation that uses the {@link HandleContext} to get
 * block information. Hevm in {@link QueryContextHevmBlocks} is abbreviated for Hedera EVM.
 */
@QueryScope
public class QueryContextHevmBlocks implements HederaEvmBlocks {
    private final QueryContext context;

    @Inject
    public QueryContextHevmBlocks(@NonNull final QueryContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash blockHashOf(final MessageFrame frame, final long blockNo) {
        final var hederaBlockHash = context.blockRecordInfo().blockHashByBlockNumber(blockNo);
        return hederaBlockHash == null ? UNAVAILABLE_BLOCK_HASH : ethHashFrom(hederaBlockHash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BlockValues blockValuesOf(final long gasLimit) {
        return HevmBlockValues.from(context.blockRecordInfo(), gasLimit);
    }
}
