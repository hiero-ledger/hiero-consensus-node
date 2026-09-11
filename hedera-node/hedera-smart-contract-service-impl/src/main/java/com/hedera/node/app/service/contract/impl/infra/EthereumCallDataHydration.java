// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.infra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_FILE_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FILE_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.populateEthTxData;
import static com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData.failureFrom;
import static com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData.successFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.removeIfAnyLeading0x;

import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData;
import com.hedera.node.app.service.file.ReadableFileStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HexFormat;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A utility that hydrates {@link EthTxData} from a {@link EthereumTransactionBody} and a {@link ReadableFileStore}.
 */
@Singleton
public class EthereumCallDataHydration {
    /**
     * Default constructor for injection.
     */
    @Inject
    public EthereumCallDataHydration() {
        // Dagger2
    }

    /// Zero-copy CharSequence wrapper around a byte[].
    private record ByteCharSequence(byte[] data) implements CharSequence {
        @Override
        public int length() {
            return data.length;
        }

        @Override
        public char charAt(int index) {
            if (index < 0 || index >= data.length) {
                throw new StringIndexOutOfBoundsException(index);
            }
            // Bitwise mask converts signed byte to unsigned integer safely
            return (char) (data[index] & 0xFF);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return new String(data);
        }
    }

    /**
     * If successful, returns the hydrated {@link EthTxData} for the given {@link EthereumTransactionBody} and
     * {@link ReadableFileStore}. Otherwise, returns a {@link HydratedEthTxData} with a non-OK status and
     * null {@link EthTxData}.
     *
     * @param body the {@link EthereumTransactionBody} to hydrate
     * @param fileStore the {@link ReadableFileStore} to hydrate from (if needed)
     * @param firstUserEntityNum the first user entity number
     * @return the final {@link EthTxData}
     */
    public HydratedEthTxData tryToHydrate(
            @NonNull final EthereumTransactionBody body,
            @NonNull final ReadableFileStore fileStore,
            final long firstUserEntityNum) {
        final var ethTxData = populateEthTxData(body.ethereumData().toByteArray());
        if (ethTxData == null) {
            return failureFrom(INVALID_ETHEREUM_TRANSACTION);
        }
        if (requiresHydration(body, ethTxData)) {
            final var callDataFileId = body.callDataOrThrow();
            if (callDataFileId.fileNum() < firstUserEntityNum) {
                return failureFrom(INVALID_FILE_ID);
            }
            final var callDataFile = fileStore.getFileLeaf(callDataFileId);
            if (callDataFile == null) {
                return failureFrom(INVALID_FILE_ID);
            }
            if (callDataFile.deleted()) {
                return failureFrom(FILE_DELETED);
            }

            final byte[] callData;
            try {
                callData = HexFormat.of().parseHex(new ByteCharSequence(removeIfAnyLeading0x(callDataFile.contents())));
            } catch (final IllegalArgumentException ignore) {
                return failureFrom(INVALID_FILE_ID);
            }

            if (callData.length == 0) {
                return failureFrom(CONTRACT_FILE_EMPTY);
            }

            return successFrom(ethTxData.replaceCallData(callData), true);
        } else {
            return successFrom(ethTxData, false);
        }
    }

    private static boolean requiresHydration(
            @NonNull final EthereumTransactionBody body, @NonNull final EthTxData ethTxData) {
        return body.hasCallData() && !ethTxData.hasCallData();
    }
}
