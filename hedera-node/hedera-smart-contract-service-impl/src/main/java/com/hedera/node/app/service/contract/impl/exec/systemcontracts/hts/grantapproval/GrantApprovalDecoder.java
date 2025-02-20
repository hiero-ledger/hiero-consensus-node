// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides help in decoding an {@link HtsCallAttempt} representing an approval into a synthetic {@link TransactionBody}.
 */
@Singleton
public class GrantApprovalDecoder {

    /**
     * Default constructor for injection.
     */
    @Inject
    public GrantApprovalDecoder() {
        // Dagger2
    }

    /**
     * @param attempt the HTS call attempt
     * @return the crypto allowance transaction body
     */
    public TransactionBody decodeGrantApproval(@NonNull final HtsCallAttempt attempt) {
        final var call = GrantApprovalTranslator.GRANT_APPROVAL.decodeCall(attempt.inputBytes());
        return TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(attempt.senderId()).build())
                .cryptoApproveAllowance(
                        grantApproval(attempt.addressIdConverter(), call.get(0), call.get(1), call.get(2)))
                .build();
    }

    /**
     * @param attempt the HTS call attempt
     * @return the crypto allowance transaction body
     */
    public TransactionBody decodeGrantApprovalNFT(@NonNull final HtsCallAttempt attempt) {
        final var call = GrantApprovalTranslator.GRANT_APPROVAL_NFT.decodeCall(attempt.inputBytes());
        return TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(attempt.senderId()).build())
                .cryptoApproveAllowance(
                        grantApprovalNFT(attempt.addressIdConverter(), call.get(0), call.get(1), call.get(2)))
                .build();
    }

    private CryptoApproveAllowanceTransactionBody grantApproval(
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final Address token,
            @NonNull final Address spender,
            @NonNull final BigInteger amount) {
        return CryptoApproveAllowanceTransactionBody.newBuilder()
                .tokenAllowances(TokenAllowance.newBuilder()
                        .tokenId(ConversionUtils.asTokenId(token))
                        .spender(addressIdConverter.convert(spender))
                        .amount(amount.longValueExact())
                        .build())
                .build();
    }

    private CryptoApproveAllowanceTransactionBody grantApprovalNFT(
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final Address token,
            @NonNull final Address spender,
            @NonNull final BigInteger serialNumber) {
        return CryptoApproveAllowanceTransactionBody.newBuilder()
                .nftAllowances(NftAllowance.newBuilder()
                        .tokenId(ConversionUtils.asTokenId(token))
                        .spender(addressIdConverter.convert(spender))
                        .serialNumbers(serialNumber.longValue())
                        .build())
                .build();
    }
}
