// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.infra;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A class that is used for easy access of Ethereum signatures.
 */
@Singleton
public class EthTxSigsCache {
    private static final int ETH_SIGS_CACHE_TTL_SECS = 15;

    private final LoadingCache<EthTxData, EthTxSigs> cache = Caffeine.newBuilder()
            .expireAfterAccess(ETH_SIGS_CACHE_TTL_SECS, TimeUnit.SECONDS)
            .softValues()
            .build(EthTxSigs::extractSignatures);

    /**
     * Default constructor for injection
     */
    @Inject
    public EthTxSigsCache() {
        // Dagger2
    }

    /**
     * @param data the Ethereum data that we want to sign
     * @return return the Ethereum signature
     */
    public EthTxSigs computeIfAbsent(@NonNull final EthTxData data) {
        // Since preHandle() is multi-threaded, we are happy to synchronously load the signatures
        // for this EthTxData if they are not already cached; with a 15s TTL, this should make the
        // subsequent lookup in handle() very fast almost always
        return cache.get(data);
    }
}
