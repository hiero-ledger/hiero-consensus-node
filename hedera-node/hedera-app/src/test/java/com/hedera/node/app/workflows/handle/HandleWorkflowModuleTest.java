// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.hints.handlers.CrsPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.handlers.HintsKeyPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsPartialSignatureHandler;
import com.hedera.node.app.hints.handlers.HintsPreprocessingVoteHandler;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.history.handlers.HistoryProofKeyPublicationHandler;
import com.hedera.node.app.history.handlers.HistoryProofSignatureHandler;
import com.hedera.node.app.history.handlers.HistoryProofVoteHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.AddressBookHandlers;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeCreateHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeDeleteHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeUpdateHandler;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusCreateTopicHandler;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusDeleteTopicHandler;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusHandlers;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusSubmitMessageHandler;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusUpdateTopicHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractCallHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractCreateHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractDeleteHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractHandlers;
import com.hedera.node.app.service.contract.impl.handlers.ContractSystemDeleteHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractSystemUndeleteHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractUpdateHandler;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.service.file.impl.handlers.FileAppendHandler;
import com.hedera.node.app.service.file.impl.handlers.FileCreateHandler;
import com.hedera.node.app.service.file.impl.handlers.FileDeleteHandler;
import com.hedera.node.app.service.file.impl.handlers.FileHandlers;
import com.hedera.node.app.service.file.impl.handlers.FileSystemDeleteHandler;
import com.hedera.node.app.service.file.impl.handlers.FileSystemUndeleteHandler;
import com.hedera.node.app.service.file.impl.handlers.FileUpdateHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.FreezeHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkAdminHandlers;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkUncheckedSubmitHandler;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleCreateHandler;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleDeleteHandler;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleHandlers;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleSignHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoAddLiveHashHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoApproveAllowanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoCreateHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteAllowanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteLiveHashHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoUpdateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenAccountWipeHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenAssociateToAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenBurnHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenCreateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenDeleteHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenDissociateFromAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenFeeScheduleUpdateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenFreezeAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGrantKycToAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenHandlers;
import com.hedera.node.app.service.token.impl.handlers.TokenMintHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenPauseHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenRevokeKycFromAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUnfreezeAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUnpauseHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUpdateHandler;
import com.hedera.node.app.service.util.impl.handlers.UtilHandlers;
import com.hedera.node.app.service.util.impl.handlers.UtilPrngHandler;
import com.hedera.node.app.workflows.dispatcher.TransactionHandlers;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleWorkflowModuleTest {
    @Mock
    private NetworkAdminHandlers networkAdminHandlers;

    @Mock
    private ConsensusHandlers consensusHandlers;

    @Mock
    private FileHandlers fileHandlers;

    @Mock
    private ContractHandlers contractHandlers;

    @Mock
    private ScheduleHandlers scheduleHandlers;

    @Mock
    private TokenHandlers tokenHandlers;

    @Mock
    private UtilHandlers utilHandlers;

    @Mock
    private AddressBookHandlers addressBookHandlers;

    @Mock
    private HintsPreprocessingVoteHandler preprocessingVoteHandler;

    @Mock
    private HintsPartialSignatureHandler partialSignatureHandler;

    @Mock
    private HintsKeyPublicationHandler hintsKeyPublicationHandler;

    @Mock
    private HistoryProofKeyPublicationHandler proofKeyPublicationHandler;

    @Mock
    private HistoryProofSignatureHandler proofSignatureHandler;

    @Mock
    private HistoryProofVoteHandler proofVoteHandler;

    @Mock
    private CrsPublicationHandler crsPublicationHandler;

    @Mock
    private ConsensusCreateTopicHandler consensusCreateTopicHandler;

    @Mock
    private ConsensusUpdateTopicHandler consensusUpdateTopicHandler;

    @Mock
    private ConsensusDeleteTopicHandler consensusDeleteTopicHandler;

    @Mock
    private ConsensusSubmitMessageHandler consensusSubmitMessageHandler;

    @Mock
    private ContractCreateHandler contractCreateHandler;

    @Mock
    private ContractUpdateHandler contractUpdateHandler;

    @Mock
    private ContractCallHandler contractCallHandler;

    @Mock
    private ContractDeleteHandler contractDeleteHandler;

    @Mock
    private ContractSystemDeleteHandler contractSystemDeleteHandler;

    @Mock
    private ContractSystemUndeleteHandler contractSystemUndeleteHandler;

    @Mock
    private EthereumTransactionHandler etherumTransactionHandler;

    @Mock
    private CryptoCreateHandler cryptoCreateHandler;

    @Mock
    private CryptoUpdateHandler cryptoUpdateHandler;

    @Mock
    private CryptoTransferHandler cryptoTransferHandler;

    @Mock
    private CryptoDeleteHandler cryptoDeleteHandler;

    @Mock
    private CryptoApproveAllowanceHandler cryptoApproveAllowanceHandler;

    @Mock
    private CryptoDeleteAllowanceHandler cryptoDeleteAllowanceHandler;

    @Mock
    private CryptoAddLiveHashHandler cryptoAddLiveHashHandler;

    @Mock
    private CryptoDeleteLiveHashHandler cryptoDeleteLiveHashHandler;

    @Mock
    private FileCreateHandler fileCreateHandler;

    @Mock
    private FileUpdateHandler fileUpdateHandler;

    @Mock
    private FileDeleteHandler fileDeleteHandler;

    @Mock
    private FileAppendHandler fileAppendHandler;

    @Mock
    private FileSystemDeleteHandler fileSystemDeleteHandler;

    @Mock
    private FileSystemUndeleteHandler fileSystemUndeleteHandler;

    @Mock
    private FreezeHandler freezeHandler;

    @Mock
    private NetworkUncheckedSubmitHandler networkUncheckedSubmitHandler;

    @Mock
    private ScheduleCreateHandler scheduleCreateHandler;

    @Mock
    private ScheduleSignHandler scheduleSignHandler;

    @Mock
    private ScheduleDeleteHandler scheduleDeleteHandler;

    @Mock
    private TokenCreateHandler tokenCreateHandler;

    @Mock
    private TokenUpdateHandler tokenUpdateHandler;

    @Mock
    private TokenMintHandler tokenMintHandler;

    @Mock
    private TokenBurnHandler tokenBurnHandler;

    @Mock
    private TokenDeleteHandler tokenDeleteHandler;

    @Mock
    private TokenAccountWipeHandler tokenAccountWipeHandler;

    @Mock
    private TokenFreezeAccountHandler tokenFreezeAccountHandler;

    @Mock
    private TokenUnfreezeAccountHandler tokenUnfreezeAccountHandler;

    @Mock
    private TokenGrantKycToAccountHandler tokenGrantKycToAccountHandler;

    @Mock
    private TokenRevokeKycFromAccountHandler tokenRevokeKycFromAccountHandler;

    @Mock
    private TokenAssociateToAccountHandler tokenAssociateToAccountHandler;

    @Mock
    private TokenDissociateFromAccountHandler tokenDissociateFromAccountHandler;

    @Mock
    private TokenFeeScheduleUpdateHandler tokenFeeScheduleUpdateHandler;

    @Mock
    private TokenPauseHandler tokenPauseHandler;

    @Mock
    private TokenUnpauseHandler tokenUnpauseHandler;

    @Mock
    private NodeCreateHandler nodeCreateHandler;

    @Mock
    private NodeUpdateHandler nodeUpdateHandler;

    @Mock
    private NodeDeleteHandler nodeDeleteHandler;

    @Mock
    private UtilPrngHandler utilPrngHandler;

    @Mock
    private HintsKeyPublicationHandler keyPublicationHandler;

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void exportsRosterToGivenPath() {
        final var path = tempDir.resolve("network.json");
        final var roster = new Roster(List.of(
                new RosterEntry(1L, 2L, Bytes.EMPTY, List.of(new ServiceEndpoint(Bytes.EMPTY, 8080, "localhost")))));

        HandleWorkflowModule.provideRosterExportHelper().accept(roster, path);

        assertThat(path).exists();
    }

    @Test
    void usesComponentsToGetHandlers() {
        given(consensusHandlers.consensusCreateTopicHandler()).willReturn(consensusCreateTopicHandler);
        given(consensusHandlers.consensusUpdateTopicHandler()).willReturn(consensusUpdateTopicHandler);
        given(consensusHandlers.consensusDeleteTopicHandler()).willReturn(consensusDeleteTopicHandler);
        given(consensusHandlers.consensusSubmitMessageHandler()).willReturn(consensusSubmitMessageHandler);
        given(contractHandlers.contractCreateHandler()).willReturn(contractCreateHandler);
        given(contractHandlers.contractUpdateHandler()).willReturn(contractUpdateHandler);
        given(contractHandlers.contractCallHandler()).willReturn(contractCallHandler);
        given(contractHandlers.contractDeleteHandler()).willReturn(contractDeleteHandler);
        given(contractHandlers.contractSystemDeleteHandler()).willReturn(contractSystemDeleteHandler);
        given(contractHandlers.contractSystemUndeleteHandler()).willReturn(contractSystemUndeleteHandler);
        given(contractHandlers.ethereumTransactionHandler()).willReturn(etherumTransactionHandler);
        given(tokenHandlers.cryptoCreateHandler()).willReturn(cryptoCreateHandler);
        given(tokenHandlers.cryptoUpdateHandler()).willReturn(cryptoUpdateHandler);
        given(tokenHandlers.cryptoTransferHandler()).willReturn(cryptoTransferHandler);
        given(tokenHandlers.cryptoDeleteHandler()).willReturn(cryptoDeleteHandler);
        given(tokenHandlers.cryptoApproveAllowanceHandler()).willReturn(cryptoApproveAllowanceHandler);
        given(tokenHandlers.cryptoDeleteAllowanceHandler()).willReturn(cryptoDeleteAllowanceHandler);
        given(tokenHandlers.cryptoAddLiveHashHandler()).willReturn(cryptoAddLiveHashHandler);
        given(tokenHandlers.cryptoDeleteLiveHashHandler()).willReturn(cryptoDeleteLiveHashHandler);
        given(fileHandlers.fileCreateHandler()).willReturn(fileCreateHandler);
        given(fileHandlers.fileUpdateHandler()).willReturn(fileUpdateHandler);
        given(fileHandlers.fileDeleteHandler()).willReturn(fileDeleteHandler);
        given(fileHandlers.fileAppendHandler()).willReturn(fileAppendHandler);
        given(fileHandlers.fileSystemDeleteHandler()).willReturn(fileSystemDeleteHandler);
        given(fileHandlers.fileSystemUndeleteHandler()).willReturn(fileSystemUndeleteHandler);
        given(networkAdminHandlers.freezeHandler()).willReturn(freezeHandler);
        given(networkAdminHandlers.networkUncheckedSubmitHandler()).willReturn(networkUncheckedSubmitHandler);
        given(scheduleHandlers.scheduleCreateHandler()).willReturn(scheduleCreateHandler);
        given(scheduleHandlers.scheduleSignHandler()).willReturn(scheduleSignHandler);
        given(scheduleHandlers.scheduleDeleteHandler()).willReturn(scheduleDeleteHandler);
        given(tokenHandlers.tokenCreateHandler()).willReturn(tokenCreateHandler);
        given(tokenHandlers.tokenUpdateHandler()).willReturn(tokenUpdateHandler);
        given(tokenHandlers.tokenMintHandler()).willReturn(tokenMintHandler);
        given(tokenHandlers.tokenBurnHandler()).willReturn(tokenBurnHandler);
        given(tokenHandlers.tokenDeleteHandler()).willReturn(tokenDeleteHandler);
        given(tokenHandlers.tokenAccountWipeHandler()).willReturn(tokenAccountWipeHandler);
        given(tokenHandlers.tokenFreezeAccountHandler()).willReturn(tokenFreezeAccountHandler);
        given(tokenHandlers.tokenUnfreezeAccountHandler()).willReturn(tokenUnfreezeAccountHandler);
        given(tokenHandlers.tokenGrantKycToAccountHandler()).willReturn(tokenGrantKycToAccountHandler);
        given(tokenHandlers.tokenRevokeKycFromAccountHandler()).willReturn(tokenRevokeKycFromAccountHandler);
        given(tokenHandlers.tokenAssociateToAccountHandler()).willReturn(tokenAssociateToAccountHandler);
        given(tokenHandlers.tokenDissociateFromAccountHandler()).willReturn(tokenDissociateFromAccountHandler);
        given(tokenHandlers.tokenFeeScheduleUpdateHandler()).willReturn(tokenFeeScheduleUpdateHandler);
        given(tokenHandlers.tokenPauseHandler()).willReturn(tokenPauseHandler);
        given(tokenHandlers.tokenUnpauseHandler()).willReturn(tokenUnpauseHandler);
        given(utilHandlers.prngHandler()).willReturn(utilPrngHandler);
        given(addressBookHandlers.nodeCreateHandler()).willReturn(nodeCreateHandler);
        given(addressBookHandlers.nodeDeleteHandler()).willReturn(nodeDeleteHandler);
        given(addressBookHandlers.nodeUpdateHandler()).willReturn(nodeUpdateHandler);

        final var hintsHandlers = new HintsHandlers(
                hintsKeyPublicationHandler, preprocessingVoteHandler, partialSignatureHandler, crsPublicationHandler);
        final var historyHandlers =
                new HistoryHandlers(proofSignatureHandler, proofKeyPublicationHandler, proofVoteHandler);
        final var handlers = HandleWorkflowModule.provideTransactionHandlers(
                networkAdminHandlers,
                consensusHandlers,
                fileHandlers,
                () -> contractHandlers,
                scheduleHandlers,
                tokenHandlers,
                utilHandlers,
                addressBookHandlers,
                hintsHandlers,
                historyHandlers);
        assertInstanceOf(TransactionHandlers.class, handlers);
    }
}
