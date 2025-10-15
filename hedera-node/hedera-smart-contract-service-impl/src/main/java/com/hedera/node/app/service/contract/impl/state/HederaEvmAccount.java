// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * A Hedera specialization of a Besu {@link MutableAccount} that provides additional Hedera-specific information.
 */
public interface HederaEvmAccount extends MutableAccount {
    /**
     * Returns a native Hedera account representation of this account.
     *
     * @return the native Hedera account
     */
    com.hedera.hapi.node.state.token.Account toNativeAccount();

    /**
     * Returns whether this account is an ERC-20/ERC-721 facade for a Hedera token.
     *
     * @return whether this account is token facade
     */
    boolean isTokenFacade();

    /**
     * Returns whether this account is a regular account.
     *
     * @return whether this account is regular account
     */
    boolean isRegularAccount();

    /**
     * Returns whether this account is a facade for a schedule transaction.
     *
     * @return whether this account is schedule transaction facade
     */
    boolean isScheduleTxnFacade();

    /**
     * Returns the Hedera account id for this account.
     *
     * @return the Hedera account id
     * @throws IllegalStateException if this account is a token facade
     */
    @NonNull
    AccountID hederaId();

    /**
     * Returns the Hedera contract id for this account.
     *
     * @return the Hedera contract id, including if the account is a token facade
     */
    @NonNull
    ContractID hederaContractId();

    /**
     * Returns the EVM code for this account. Added here to avoid client code needing to manage a
     * cache of {@link org.hyperledger.besu.evm.Code} wrappers around raw bytecode returned by
     * {@link Account#getCode()}.
     *
     * @param functionSelector the function selector to use when fetching the code.  If more than 4 bytes for the
     *                         function selector is passed in, only the first 4 bytes will be used.
     *                         Only relevant for regular accounts.
     *
     * @param codeFactory the factory used to construct an instance of {@link org.hyperledger.besu.evm.Code}
     *      *                    from the raw bytecode associated with this account.
     * @return the EVM code for this account
     */
    @NonNull
    Code getEvmCode(@NonNull final Bytes functionSelector, @NonNull CodeFactory codeFactory);
}
