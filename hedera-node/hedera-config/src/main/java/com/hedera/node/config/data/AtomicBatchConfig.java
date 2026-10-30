// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.types.HederaFunctionalitySet;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

// Spotless requires way too many newlines, and ends up breaking the string because it forces too many indents.
// spotless:off
@ConfigData("atomicBatch")
public record AtomicBatchConfig(
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean isEnabled,
        @ConfigProperty(defaultValue = "50") @NetworkProperty long maxNumberOfTransactions,
        // The only types allowed as inner transactions; a new type stays out of batches until it is added here
        @ConfigProperty(defaultValue =
            "CryptoTransfer,CryptoUpdate,CryptoDelete,ContractCall,ContractCreate,ContractUpdate,FileCreate,"
                + "FileAppend,FileUpdate,FileDelete,CryptoCreate,ContractDelete,"
                + "ConsensusCreateTopic,ConsensusUpdateTopic,ConsensusDeleteTopic,ConsensusSubmitMessage,"
                + "TokenCreate,TokenFreezeAccount,TokenUnfreezeAccount,TokenGrantKycToAccount,"
                + "TokenRevokeKycFromAccount,TokenDelete,TokenUpdate,TokenMint,TokenBurn,TokenAccountWipe,"
                + "TokenAssociateToAccount,TokenDissociateFromAccount,ScheduleDelete,TokenFeeScheduleUpdate,"
                + "TokenPause,TokenUnpause,CryptoApproveAllowance,CryptoDeleteAllowance,EthereumTransaction,"
                + "UtilPrng,TokenUpdateNfts,NodeCreate,NodeUpdate,NodeDelete,TokenReject,TokenAirdrop,"
                + "TokenCancelAirdrop,TokenClaimAirdrop,HookStore,RegisteredNodeCreate,RegisteredNodeUpdate,"
                + "RegisteredNodeDelete")
                @NetworkProperty HederaFunctionalitySet whitelist) {}
// spotless:on
