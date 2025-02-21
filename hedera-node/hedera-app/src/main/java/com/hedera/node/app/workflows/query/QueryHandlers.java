// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import com.hedera.node.app.service.consensus.impl.handlers.ConsensusGetTopicInfoHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractCallLocalHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetBySolidityIDHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetBytecodeHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetInfoHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetRecordsHandler;
import com.hedera.node.app.service.file.impl.handlers.FileGetContentsHandler;
import com.hedera.node.app.service.file.impl.handlers.FileGetInfoHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkGetAccountDetailsHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkGetByKeyHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkGetExecutionTimeHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkGetVersionInfoHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkTransactionGetFastRecordHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkTransactionGetReceiptHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkTransactionGetRecordHandler;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleGetInfoHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountBalanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountInfoHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountRecordsHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetLiveHashHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoGetStakersHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGetAccountNftInfosHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGetInfoHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGetNftInfoHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGetNftInfosHandler;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A record that contains all {@link com.hedera.node.app.spi.workflows.QueryHandler}s that are available in the app
 */
public record QueryHandlers(
        @NonNull ConsensusGetTopicInfoHandler consensusGetTopicInfoHandler,
        @NonNull ContractGetBySolidityIDHandler contractGetBySolidityIDHandler,
        @NonNull ContractCallLocalHandler contractCallLocalHandler,
        @NonNull ContractGetInfoHandler contractGetInfoHandler,
        @NonNull ContractGetBytecodeHandler contractGetBytecodeHandler,
        @NonNull ContractGetRecordsHandler contractGetRecordsHandler,
        @NonNull CryptoGetAccountBalanceHandler cryptoGetAccountBalanceHandler,
        @NonNull CryptoGetAccountInfoHandler cryptoGetAccountInfoHandler,
        @NonNull CryptoGetAccountRecordsHandler cryptoGetAccountRecordsHandler,
        @NonNull CryptoGetLiveHashHandler cryptoGetLiveHashHandler,
        @NonNull CryptoGetStakersHandler cryptoGetStakersHandler,
        @NonNull FileGetContentsHandler fileGetContentsHandler,
        @NonNull FileGetInfoHandler fileGetInfoHandler,
        @NonNull NetworkGetAccountDetailsHandler networkGetAccountDetailsHandler,
        @NonNull NetworkGetByKeyHandler networkGetByKeyHandler,
        @NonNull NetworkGetExecutionTimeHandler networkGetExecutionTimeHandler,
        @NonNull NetworkGetVersionInfoHandler networkGetVersionInfoHandler,
        @NonNull NetworkTransactionGetReceiptHandler networkTransactionGetReceiptHandler,
        @NonNull NetworkTransactionGetRecordHandler networkTransactionGetRecordHandler,
        @NonNull NetworkTransactionGetFastRecordHandler networkTransactionGetFastRecordHandler,
        @NonNull ScheduleGetInfoHandler scheduleGetInfoHandler,
        @NonNull TokenGetInfoHandler tokenGetInfoHandler,
        @NonNull TokenGetAccountNftInfosHandler tokenGetAccountNftInfosHandler,
        @NonNull TokenGetNftInfoHandler tokenGetNftInfoHandler,
        @NonNull TokenGetNftInfosHandler tokenGetNftInfosHandler) {}
