// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.utils;

import com.hedera.services.bdd.junit.hedera.SystemFunctionalityTarget;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import edu.umd.cs.findbugs.annotations.NonNull;

public class GrpcUtils {
    private GrpcUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Sends a query to the requested node's gRPC service for the given functionality
     * using the given clients without TLS.
     *
     * @param query the query to send
     * @param clients the clients to use
     * @param functionality the functionality to query
     * @param nodeAccountId the node to send the query to
     * @param asNodeOperator whether to send the query to the node operator port
     * @return the response from the query
     */
    public static Response send(
            @NonNull final Query query,
            @NonNull final HapiClients clients,
            @NonNull final HederaFunctionality functionality,
            @NonNull final com.hederahashgraph.api.proto.java.AccountID nodeAccountId,
            final boolean asNodeOperator) {
        return switch (functionality) {
            case ConsensusGetTopicInfo ->
                clients.getConsSvcStub(nodeAccountId, false, asNodeOperator).getTopicInfo(query);
            case GetBySolidityID ->
                clients.getScSvcStub(nodeAccountId, false, asNodeOperator).getBySolidityID(query);
            case ContractCallLocal ->
                clients.getScSvcStub(nodeAccountId, false, asNodeOperator).contractCallLocalMethod(query);
            case ContractGetInfo ->
                clients.getScSvcStub(nodeAccountId, false, asNodeOperator).getContractInfo(query);
            case ContractGetBytecode ->
                clients.getScSvcStub(nodeAccountId, false, asNodeOperator).contractGetBytecode(query);
            case ContractGetRecords ->
                clients.getScSvcStub(nodeAccountId, false, asNodeOperator).getTxRecordByContractID(query);
            case CryptoGetAccountBalance ->
                clients.getCryptoSvcStub(nodeAccountId, false, asNodeOperator).cryptoGetBalance(query);
            case CryptoGetAccountRecords ->
                clients.getCryptoSvcStub(nodeAccountId, false, asNodeOperator).getAccountRecords(query);
            case CryptoGetInfo ->
                clients.getCryptoSvcStub(nodeAccountId, false, asNodeOperator).getAccountInfo(query);
            case CryptoGetLiveHash ->
                clients.getCryptoSvcStub(nodeAccountId, false, asNodeOperator).getLiveHash(query);
            case FileGetContents ->
                clients.getFileSvcStub(nodeAccountId, false, asNodeOperator).getFileContent(query);
            case FileGetInfo ->
                clients.getFileSvcStub(nodeAccountId, false, asNodeOperator).getFileInfo(query);
            case TransactionGetReceipt ->
                clients.getCryptoSvcStub(nodeAccountId, false, asNodeOperator).getTransactionReceipts(query);
            case TransactionGetRecord ->
                clients.getCryptoSvcStub(nodeAccountId, false, asNodeOperator).getTxRecordByTxID(query);
            case GetVersionInfo ->
                clients.getNetworkSvcStub(nodeAccountId, false, asNodeOperator).getVersionInfo(query);
            case TokenGetInfo ->
                clients.getTokenSvcStub(nodeAccountId, false, asNodeOperator).getTokenInfo(query);
            case ScheduleGetInfo ->
                clients.getScheduleSvcStub(nodeAccountId, false, asNodeOperator).getScheduleInfo(query);
            case TokenGetNftInfo ->
                clients.getTokenSvcStub(nodeAccountId, false, asNodeOperator).getTokenNftInfo(query);
            case NetworkGetExecutionTime ->
                clients.getNetworkSvcStub(nodeAccountId, false, asNodeOperator).getExecutionTime(query);
            case GetAccountDetails ->
                clients.getNetworkSvcStub(nodeAccountId, false, asNodeOperator).getAccountDetails(query);
            default -> throw new IllegalArgumentException(functionality + " is not a query");
        };
    }

    /**
     * Submits a transaction to the requested node's gRPC service for the given functionality
     * using the given clients without TLS.
     *
     * @param transaction the transaction to submit
     * @param clients the clients to use
     * @param functionality the functionality to query
     * @param target the target to use, given a system functionality
     * @param nodeAccountId the node to send the query to
     * @return the response from the query
     */
    public static TransactionResponse submit(
            @NonNull final Transaction transaction,
            @NonNull final HapiClients clients,
            @NonNull final HederaFunctionality functionality,
            @NonNull final SystemFunctionalityTarget target,
            @NonNull final com.hederahashgraph.api.proto.java.AccountID nodeAccountId) {
        return switch (functionality) {
            case ContractCall ->
                clients.getScSvcStub(nodeAccountId, false, false).contractCallMethod(transaction);
            case ContractCreate ->
                clients.getScSvcStub(nodeAccountId, false, false).createContract(transaction);
            case ContractUpdate ->
                clients.getScSvcStub(nodeAccountId, false, false).updateContract(transaction);
            case ContractDelete ->
                clients.getScSvcStub(nodeAccountId, false, false).deleteContract(transaction);
            case EthereumTransaction ->
                clients.getScSvcStub(nodeAccountId, false, false).callEthereum(transaction);
            case LambdaSStore ->
                clients.getScSvcStub(nodeAccountId, false, false).lambdaSStore(transaction);
            case CryptoAddLiveHash ->
                clients.getCryptoSvcStub(nodeAccountId, false, false).addLiveHash(transaction);
            case CryptoApproveAllowance ->
                clients.getCryptoSvcStub(nodeAccountId, false, false).approveAllowances(transaction);
            case CryptoDeleteAllowance ->
                clients.getCryptoSvcStub(nodeAccountId, false, false).deleteAllowances(transaction);
            case CryptoCreate ->
                clients.getCryptoSvcStub(nodeAccountId, false, false).createAccount(transaction);
            case CryptoDelete ->
                clients.getCryptoSvcStub(nodeAccountId, false, false).cryptoDelete(transaction);
            case CryptoDeleteLiveHash ->
                clients.getCryptoSvcStub(nodeAccountId, false, false).deleteLiveHash(transaction);
            case CryptoTransfer ->
                clients.getCryptoSvcStub(nodeAccountId, false, false).cryptoTransfer(transaction);
            case CryptoUpdate ->
                clients.getCryptoSvcStub(nodeAccountId, false, false).updateAccount(transaction);
            case FileAppend ->
                clients.getFileSvcStub(nodeAccountId, false, false).appendContent(transaction);
            case FileCreate ->
                clients.getFileSvcStub(nodeAccountId, false, false).createFile(transaction);
            case FileDelete ->
                clients.getFileSvcStub(nodeAccountId, false, false).deleteFile(transaction);
            case FileUpdate ->
                clients.getFileSvcStub(nodeAccountId, false, false).updateFile(transaction);
            case SystemDelete ->
                switch (target) {
                    case CONTRACT ->
                        clients.getScSvcStub(nodeAccountId, false, false).systemDelete(transaction);
                    case FILE ->
                        clients.getFileSvcStub(nodeAccountId, false, false).systemDelete(transaction);
                    case NA -> throw new IllegalArgumentException("SystemDelete target not available");
                };
            case SystemUndelete ->
                switch (target) {
                    case CONTRACT ->
                        clients.getScSvcStub(nodeAccountId, false, false).systemUndelete(transaction);
                    case FILE ->
                        clients.getFileSvcStub(nodeAccountId, false, false).systemUndelete(transaction);
                    case NA -> throw new IllegalArgumentException("SystemUndelete target not available");
                };
            case Freeze -> clients.getFreezeSvcStub(nodeAccountId, false, false).freeze(transaction);
            case ConsensusCreateTopic ->
                clients.getConsSvcStub(nodeAccountId, false, false).createTopic(transaction);
            case ConsensusUpdateTopic ->
                clients.getConsSvcStub(nodeAccountId, false, false).updateTopic(transaction);
            case ConsensusDeleteTopic ->
                clients.getConsSvcStub(nodeAccountId, false, false).deleteTopic(transaction);
            case ConsensusSubmitMessage ->
                clients.getConsSvcStub(nodeAccountId, false, false).submitMessage(transaction);
            case UncheckedSubmit ->
                clients.getNetworkSvcStub(nodeAccountId, false, false).uncheckedSubmit(transaction);
            case TokenCreate ->
                clients.getTokenSvcStub(nodeAccountId, false, false).createToken(transaction);
            case TokenFreezeAccount ->
                clients.getTokenSvcStub(nodeAccountId, false, false).freezeTokenAccount(transaction);
            case TokenUnfreezeAccount ->
                clients.getTokenSvcStub(nodeAccountId, false, false).unfreezeTokenAccount(transaction);
            case TokenGrantKycToAccount ->
                clients.getTokenSvcStub(nodeAccountId, false, false).grantKycToTokenAccount(transaction);
            case TokenRevokeKycFromAccount ->
                clients.getTokenSvcStub(nodeAccountId, false, false).revokeKycFromTokenAccount(transaction);
            case TokenDelete ->
                clients.getTokenSvcStub(nodeAccountId, false, false).deleteToken(transaction);
            case TokenUpdate ->
                clients.getTokenSvcStub(nodeAccountId, false, false).updateToken(transaction);
            case TokenMint ->
                clients.getTokenSvcStub(nodeAccountId, false, false).mintToken(transaction);
            case TokenBurn ->
                clients.getTokenSvcStub(nodeAccountId, false, false).burnToken(transaction);
            case TokenAccountWipe ->
                clients.getTokenSvcStub(nodeAccountId, false, false).wipeTokenAccount(transaction);
            case TokenAssociateToAccount ->
                clients.getTokenSvcStub(nodeAccountId, false, false).associateTokens(transaction);
            case TokenReject ->
                clients.getTokenSvcStub(nodeAccountId, false, false).rejectToken(transaction);
            case TokenDissociateFromAccount ->
                clients.getTokenSvcStub(nodeAccountId, false, false).dissociateTokens(transaction);
            case TokenFeeScheduleUpdate ->
                clients.getTokenSvcStub(nodeAccountId, false, false).updateTokenFeeSchedule(transaction);
            case TokenPause ->
                clients.getTokenSvcStub(nodeAccountId, false, false).pauseToken(transaction);
            case TokenUnpause ->
                clients.getTokenSvcStub(nodeAccountId, false, false).unpauseToken(transaction);
            case ScheduleCreate ->
                clients.getScheduleSvcStub(nodeAccountId, false, false).createSchedule(transaction);
            case ScheduleDelete ->
                clients.getScheduleSvcStub(nodeAccountId, false, false).deleteSchedule(transaction);
            case ScheduleSign ->
                clients.getScheduleSvcStub(nodeAccountId, false, false).signSchedule(transaction);
            case UtilPrng -> clients.getUtilSvcStub(nodeAccountId, false, false).prng(transaction);
            case TokenUpdateNfts ->
                clients.getTokenSvcStub(nodeAccountId, false, false).updateNfts(transaction);
            case NodeDelete ->
                clients.getAddressBookSvcStub(nodeAccountId, false, false).deleteNode(transaction);
            case NodeCreate ->
                clients.getAddressBookSvcStub(nodeAccountId, false, false).createNode(transaction);
            case NodeUpdate ->
                clients.getAddressBookSvcStub(nodeAccountId, false, false).updateNode(transaction);
            case TokenAirdrop ->
                clients.getTokenSvcStub(nodeAccountId, false, false).airdropTokens(transaction);
            case TokenCancelAirdrop ->
                clients.getTokenSvcStub(nodeAccountId, false, false).cancelAirdrop(transaction);
            case TokenClaimAirdrop ->
                clients.getTokenSvcStub(nodeAccountId, false, false).claimAirdrop(transaction);
            case AtomicBatch ->
                clients.getUtilSvcStub(nodeAccountId, false, false).atomicBatch(transaction);
            case StateSignatureTransaction,
                    HintsPreprocessingVote,
                    HintsKeyPublication,
                    HintsPartialSignature,
                    HistoryProofKeyPublication,
                    HistoryAssemblySignature,
                    HistoryProofVote,
                    CrsPublication,
                    HookDispatch,
                    NodeStakeUpdate ->
                clients.getUtilSvcStub(nodeAccountId, false, false).prng(transaction);
            default -> throw new IllegalArgumentException(functionality + " is not a transaction");
        };
    }
}
