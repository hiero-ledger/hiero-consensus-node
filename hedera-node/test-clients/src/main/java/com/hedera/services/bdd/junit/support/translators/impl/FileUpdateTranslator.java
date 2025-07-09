// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.ServicesConfigurationList;
import com.hedera.hapi.node.base.Setting;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Translates a file update transaction into a {@link SingleTransactionRecord}, updating
 * {@link BaseTranslator} context when a special file is changed.
 */
public class FileUpdateTranslator implements BlockTransactionPartsTranslator {
    private static final String NONCE_EXTERNALIZATION_PROP = "contracts.nonces.externalization.enabled";

    public static final long EXCHANGE_RATES_FILE_NUM = 112L;
    public static final long APP_PROPERTIES_FILE_NUM = 121L;

    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @Nullable final List<TraceData> tracesSoFar,
            @NonNull final List<TraceData> followingUnitTraces) {
        requireNonNull(parts);
        requireNonNull(baseTranslator);
        requireNonNull(remainingStateChanges);
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> {
                    if (parts.status() == SUCCESS) {
                        for (final var stateChange : remainingStateChanges) {
                            if (stateChange.hasMapUpdate()
                                    && stateChange
                                            .mapUpdateOrThrow()
                                            .keyOrThrow()
                                            .hasFileIdKey()) {
                                final var fileId = stateChange
                                        .mapUpdateOrThrow()
                                        .keyOrThrow()
                                        .fileIdKeyOrThrow();
                                // when the fileUpdate transaction is inside an AtomicBatch don't set exchange rate
                                // on the translated record
                                if (fileId.fileNum() == EXCHANGE_RATES_FILE_NUM) {
                                    baseTranslator.updateActiveRates(stateChange);
                                    if (!parts.body().hasBatchKey()) {
                                        receiptBuilder.exchangeRate(baseTranslator.activeRates());
                                    }
                                }
                                if (fileId.fileNum() == APP_PROPERTIES_FILE_NUM) {
                                    final var contents = stateChange
                                            .mapUpdateOrThrow()
                                            .valueOrThrow()
                                            .fileValueOrThrow()
                                            .contents();
                                    try {
                                        final var configs = ServicesConfigurationList.PROTOBUF.parse(contents);
                                        final boolean externalizingNonces = configs.nameValue().stream()
                                                .filter(s -> s.name().equals(NONCE_EXTERNALIZATION_PROP))
                                                .map(Setting::value)
                                                .map(Boolean::parseBoolean)
                                                .findAny()
                                                .orElse(Boolean.TRUE);
                                        baseTranslator.toggleNoncesExternalization(externalizingNonces);
                                    } catch (ParseException ignore) {
                                        baseTranslator.toggleNoncesExternalization(true);
                                    }
                                }
                            }
                        }
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }
}
