// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto;

import static com.hedera.services.bdd.suites.HapiSuite.*;
import static com.hedera.services.bdd.suites.crypto.LeakyCryptoTestsSuite.PAY_TXN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import java.util.Optional;

public class EthereumTransferToRandomEVMAddress implements OpProvider {
    private final EntityNameProvider keys;
    private final HapiSpecRegistry registry;
    private static int nonce = 0;

    public EthereumTransferToRandomEVMAddress(HapiSpecRegistry registry, EntityNameProvider keys) {
        this.registry = registry;
        this.keys = keys;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        return randomKey().map(this::generateLazyCreateEthereumTransaction);
    }

    private Optional<String> randomKey() {
        return keys.getQualifying().filter(k -> !k.isEmpty());
    }

    /**
     * Testing Lazy Create with random EVM addresses.
     * Operation: creation of random evm addresses and sending hbars through Ethereum Transaction (Lazy Create)
     *
     * @param addressRecipient evm address of the recipient
     */
    private HapiEthereumCall generateLazyCreateEthereumTransaction(String addressRecipient) {
        HapiEthereumCall ethereumCall = TxnVerbs.ethereumCryptoTransferToAlias(
                        getEvmAddress(addressRecipient), FIVE_HBARS)
                .type(EthTxData.EthTransactionType.EIP1559)
                .signingWith(SECP_256K1_SOURCE_KEY)
                .payingWith(RELAYER)
                .nonce(nonce) // must be incremented sequentially on every successful transactions
                .maxFeePerGas(0L)
                .maxGasAllowance(FIVE_HBARS)
                .gasLimit(2_000_000L)
                .via(PAY_TXN)
                // Since the receiver _could_ have receiverSigRequired=true (c.f. the
                // InitialAccountIdentifiers.customize() method), INVALID_SIGNATURE is a valid
                // response code
                .hasKnownStatusFrom(SUCCESS, INVALID_SIGNATURE, WRONG_NONCE);

        incrementNonce();

        return ethereumCall;
    }

    private static void incrementNonce() {
        nonce++;
    }

    private ByteString getEvmAddress(String keyName) {
        return this.registry.getKey(keyName).getECDSASecp256K1();
    }
}
