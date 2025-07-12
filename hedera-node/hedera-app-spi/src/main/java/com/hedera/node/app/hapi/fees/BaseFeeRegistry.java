package com.hedera.node.app.hapi.fees;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class BaseFeeRegistry {

    private static final Map<String, Double> BASE_FEES;

    static {
        Map<String, Double> fees = new HashMap<>();
        // Addons
        fees.put("PerSignature", 0.0001);
        fees.put("PerKey", 0.01);
        fees.put("PerHCSByte", 0.000011);
        fees.put("PerFileByte", 0.000011);
        fees.put("PerCryptoTransferAccount", 0.00001);
        fees.put("PerGas", 0.0000000852);

        // Crypto service
        fees.put("CryptoCreate", 0.05000);
        fees.put("CryptoUpdate", 0.00022);
        fees.put("CryptoTransfer", 0.00010);
        fees.put("CryptoDelete", 0.00500);
        fees.put("CryptoGetAccountRecords", 0.00010);
        fees.put("CryptoGetAccountBalance", 0.00000);
        fees.put("CryptoGetInfo", 0.00010);
        fees.put("CryptoGetStakers", 0.00010);
        fees.put("CryptoApproveAllowance", 0.05000);
        fees.put("CryptoDeleteAllowance", 0.05000);

        // HCS
        fees.put("ConsensusCreateTopic", 0.01000);
        fees.put("ConsensusCreateTopicWithCustomFee", 2.0);
        fees.put("ConsensusUpdateTopic", 0.00022);
        fees.put("ConsensusDeleteTopic", 0.00500);
        fees.put("ConsensusSubmitMessage", 0.00010);
        fees.put("ConsensusSubmitMessageWithCustomFee", 0.05000);
        fees.put("ConsensusGetTopicInfo", 0.00010);

        // HTS
        fees.put("TokenCreate", 1.00000);
        fees.put("TokenCreateWithCustomFee", 2.00000);
        fees.put("TokenDelete", 0.00100);
        fees.put("TokenUpdate", 0.00100);
        fees.put("TokenMintFungible", 0.00100);
        fees.put("TokenMintNonFungible", 0.02000);
        fees.put("TokenBurn", 0.00100);
        fees.put("TokenPause", 0.00100);
        fees.put("TokenUnpause", 0.00100);
        fees.put("TokenGetInfo", 0.00010);
        fees.put("TokenGrantKycToAccount", 0.00100);
        fees.put("TokenRevokeKycFromAccount", 0.00100);
        fees.put("TokenFreezeAccount", 0.00100);
        fees.put("TokenUnfreezeAccount", 0.00100);
        fees.put("TokenAccountWipe", 0.00100);
        fees.put("TokenAssociateToAccount", 0.05000);
        fees.put("TokenDissociateFromAccount", 0.05000);
        fees.put("TokenTransfer", 0.001);
        fees.put("TokenTransferWithCustomFee", 0.002);
        fees.put("TokenAirdrop", 0.10000);
        fees.put("TokenAirdropWithCustomFee", 0.10100);
        fees.put("TokenClaimAirdrop", 0.00100);
        fees.put("TokenCancelAirdrop", 0.00100);
        fees.put("TokenReject", 0.00100);
        fees.put("TokenFeeScheduleUpdate", 0.00100);
        fees.put("GetAccountNftInfo", 0.00010);
        fees.put("GetTokenNftInfo", 0.00010);
        fees.put("GetTokenNftInfos", 0.00010);

        // Scheduled Transactions
        fees.put("ScheduleCreate", 0.01000);
        fees.put("ScheduleSign", 0.00100);
        fees.put("ScheduleDelete", 0.00100);
        fees.put("ScheduleGetInfo", 0.00010);

        // Smart Contracts

        fees.put("ContractCreate", 1.00000);
        fees.put("ContractUpdate", 0.02600);
        fees.put("ContractDelete", 0.00700);
        fees.put("ContractCall", 0.00000);
        fees.put("ContractCallLocal", 0.00100);
        fees.put("ContractGetBytecode", 0.05000);
        fees.put("GetBySolidityID", 0.00010);
        fees.put("ContractGetInfo", 0.00010);
        fees.put("ContractGetRecords", 0.00010);
        fees.put("EthereumTransactionSuccess", 0.00000);
        fees.put("EthereumTransactionFail", 0.00010);
        // Files
        fees.put("FileCreate", 0.05000);
        fees.put("FileUpdate", 0.05000);
        fees.put("FileDelete", 0.00700);
        fees.put("FileAppend", 0.05000);
        fees.put("FileGetContents", 0.00010);
        fees.put("FileGetInfo", 0.00010);

        // Misc
        fees.put("GetVersionInfo", 0.00010);
        fees.put("TransactionGetReceipt", 0.00000);
        fees.put("TransactionGetRecord", 0.00010);
        fees.put("SystemDelete", 0.00000);
        fees.put("SystemUndelete", 0.00000);
        fees.put("CreateNode", 0.00100);
        fees.put("DeleteNode", 0.00100);
        fees.put("UpdateNode", 0.00100);
        fees.put("PrngTransaction", 0.00100);
        fees.put("BatchTransaction", 0.00100);

        BASE_FEES = Collections.unmodifiableMap(fees);
    }

    private BaseFeeRegistry() {
        // prevent instantiation
    }

    public static double getBaseFee(String api) {
        return BASE_FEES.getOrDefault(api, 0.0);
    }

    public static Map<String, Double> getAllBaseFees() {
        return BASE_FEES;
    }

}
