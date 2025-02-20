// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_RES_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.FEE_MATRICES_CONST;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.network.NetworkGetVersionInfoResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.VersionConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#GET_VERSION_INFO}.
 * <p>
 * This query is used to get the version info of the node.
 */
@Singleton
public class NetworkGetVersionInfoHandler extends PaidQueryHandler {
    private static final com.hedera.hapi.node.base.FeeComponents ZERO_USAGE =
            com.hedera.hapi.node.base.FeeComponents.DEFAULT;

    private static final int BYTES_PER_SEMANTIC_VERSION = 12;
    private static final com.hedera.hapi.node.base.FeeComponents GET_VERSION_INFO_NODE_USAGE =
            com.hedera.hapi.node.base.FeeComponents.newBuilder()
                    .constant(FEE_MATRICES_CONST)
                    .bpt(BASIC_QUERY_HEADER)
                    .bpr(BASIC_QUERY_RES_HEADER + 2 * BYTES_PER_SEMANTIC_VERSION)
                    .build();
    private static final com.hedera.hapi.node.base.FeeData FIXED_USAGE = com.hedera.hapi.node.base.FeeData.newBuilder()
            .networkdata(ZERO_USAGE)
            .servicedata(ZERO_USAGE)
            .nodedata(GET_VERSION_INFO_NODE_USAGE)
            .build();

    @Inject
    public NetworkGetVersionInfoHandler() {
        // Exists for injection
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.networkGetVersionInfoOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = NetworkGetVersionInfoResponse.newBuilder().header(header);
        return Response.newBuilder().networkGetVersionInfo(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var query = context.query();
        query.networkGetVersionInfoOrThrow();
        // no further validation needed - NetworkGetVersionInfoQuery does not require any parameters
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var versionConfig = context.configuration().getConfigData(VersionConfig.class);
        final var hederaConfig = context.configuration().getConfigData(HederaConfig.class);
        final var query = context.query();
        final var op = query.networkGetVersionInfoOrThrow();
        final NetworkGetVersionInfoResponse.Builder responseBuilder = NetworkGetVersionInfoResponse.newBuilder();
        final ResponseType responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        responseBuilder.header(header);
        if (header.nodeTransactionPrecheckCode() == OK && responseType != COST_ANSWER) {
            final var servicesVersion = (hederaConfig.configVersion() == 0)
                    ? versionConfig.servicesVersion()
                    : versionConfig
                            .servicesVersion()
                            .copyBuilder()
                            .build("" + hederaConfig.configVersion())
                            .build();
            responseBuilder.hederaServicesVersion(servicesVersion).hapiProtoVersion(versionConfig.hapiVersion());
        }

        return Response.newBuilder().networkGetVersionInfo(responseBuilder).build();
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull final QueryContext queryContext) {
        requireNonNull(queryContext);

        return queryContext.feeCalculator().legacyCalculate(sigValueObj -> CommonPbjConverters.fromPbj(FIXED_USAGE));
    }
}
