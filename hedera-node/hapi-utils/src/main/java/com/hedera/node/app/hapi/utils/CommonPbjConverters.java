// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.ByteStringUtils.unwrapUnsafelyIfPossible;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.*;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleInfo;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.hederahashgraph.api.proto.java.AccountID.AccountCase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

public class CommonPbjConverters {
    public static @NonNull com.hederahashgraph.api.proto.java.Query fromPbj(@NonNull Query query) {
        requireNonNull(query);
        try {
            final var bytes = asBytes(Query.PROTOBUF, query);
            return com.hederahashgraph.api.proto.java.Query.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    @NonNull
    public static com.hederahashgraph.api.proto.java.File fromPbj(@Nullable File file) {
        var builder = com.hederahashgraph.api.proto.java.File.newBuilder();
        if (file != null) {
            builder.setFileId(fromPbj(file.fileIdOrThrow()));
            builder.setExpirationSecond(file.expirationSecond());
            builder.setKeys(pbjToProto(
                    file.keysOrElse(KeyList.DEFAULT), KeyList.class, com.hederahashgraph.api.proto.java.KeyList.class));
            builder.setContents(ByteString.copyFrom(file.contents().toByteArray()));
            builder.setMemo(file.memo());
            builder.setDeleted(file.deleted());
        }
        return builder.build();
    }

    public static @NonNull com.hederahashgraph.api.proto.java.EntityNumber fromPbj(@NonNull EntityNumber entityNumber) {
        requireNonNull(entityNumber);
        return com.hederahashgraph.api.proto.java.EntityNumber.newBuilder()
                .setNumber(entityNumber.number())
                .build();
    }

    public static @NonNull com.hederahashgraph.api.proto.java.TransactionBody fromPbj(@NonNull TransactionBody tx) {
        requireNonNull(tx);
        try {
            final var bytes = asBytes(TransactionBody.PROTOBUF, tx);
            return com.hederahashgraph.api.proto.java.TransactionBody.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    public static @NonNull List<com.hederahashgraph.api.proto.java.ServiceEndpoint> fromPbj(
            @NonNull final List<ServiceEndpoint> endpoint) {
        requireNonNull(endpoint);
        return endpoint.stream().map(CommonPbjConverters::fromPbj).toList();
    }

    public static @NonNull List<ServiceEndpoint> toPbj(
            @NonNull final List<com.hederahashgraph.api.proto.java.ServiceEndpoint> endpoint) {
        requireNonNull(endpoint);
        return endpoint.stream().map(CommonPbjConverters::toPbj).toList();
    }

    public static @NonNull com.hederahashgraph.api.proto.java.ServiceEndpoint fromPbj(
            @NonNull ServiceEndpoint endpoint) {
        requireNonNull(endpoint);
        try {
            final var bytes = asBytes(ServiceEndpoint.PROTOBUF, endpoint);
            return com.hederahashgraph.api.proto.java.ServiceEndpoint.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    public static @NonNull com.hederahashgraph.api.proto.java.Key fromPbj(@NonNull Key keyValue) {
        requireNonNull(keyValue);
        try {
            final var bytes = asBytes(Key.PROTOBUF, keyValue);
            return com.hederahashgraph.api.proto.java.Key.parseFrom(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> byte[] asBytes(@NonNull Codec<T> codec, @NonNull T tx) {
        requireNonNull(codec);
        requireNonNull(tx);
        try {
            final var bytes = new ByteArrayOutputStream();
            codec.write(tx, new WritableStreamingData(bytes));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unable to convert from PBJ to bytes", e);
        }
    }

    public static @NonNull byte[] asBytes(@NonNull Bytes b) {
        final var buf = new byte[Math.toIntExact(b.length())];
        b.getBytes(0, buf);
        return buf;
    }

    /**
     * Tries to convert a PBJ {@link ResponseType} to a {@link com.hederahashgraph.api.proto.java.ResponseType}
     *
     * @param responseType the PBJ {@link ResponseType} to convert
     * @return the converted {@link com.hederahashgraph.api.proto.java.ResponseType} if valid
     */
    public static @NonNull com.hederahashgraph.api.proto.java.ResponseType fromPbjResponseType(
            @NonNull final ResponseType responseType) {
        return switch (requireNonNull(responseType)) {
            case ANSWER_ONLY -> com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;
            case ANSWER_STATE_PROOF -> com.hederahashgraph.api.proto.java.ResponseType.ANSWER_STATE_PROOF;
            case COST_ANSWER -> com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER;
            case COST_ANSWER_STATE_PROOF -> com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER_STATE_PROOF;
        };
    }

    /**
     * Given a PBJ type, converts it to a proto type.
     * @param pbj the PBJ type
     * @param pbjClass the PBJ class
     * @param protoClass the proto class
     * @return the proto type
     * @param <T> the PBJ type
     * @param <R> the proto type
     */
    public static <T, R extends GeneratedMessage> R pbjToProto(
            final T pbj, final Class<T> pbjClass, final Class<R> protoClass) {
        try {
            final var codecField = pbjClass.getDeclaredField("PROTOBUF");
            final var codec = (Codec<T>) codecField.get(null);
            final var bytes = asBytes(codec, pbj);
            final var protocParser = protoClass.getMethod("parseFrom", byte[].class);
            return (R) protocParser.invoke(null, bytes);
        } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            // Should be impossible, so just propagate an exception
            throw new RuntimeException("Invalid conversion to proto for " + pbjClass.getSimpleName(), e);
        }
    }

    public static com.hederahashgraph.api.proto.java.FileID fromPbj(final FileID someFileId) {
        return com.hederahashgraph.api.proto.java.FileID.newBuilder()
                .setRealmNum(someFileId.realmNum())
                .setShardNum(someFileId.shardNum())
                .setFileNum(someFileId.fileNum())
                .build();
    }

    public static @NonNull com.hederahashgraph.api.proto.java.TransactionRecord fromPbj(@NonNull TransactionRecord tx) {
        requireNonNull(tx);
        try {
            final var bytes = asBytes(TransactionRecord.PROTOBUF, tx);
            return com.hederahashgraph.api.proto.java.TransactionRecord.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    public static com.hederahashgraph.api.proto.java.CustomFee fromPbj(@NonNull final CustomFee customFee) {
        return explicitPbjToProto(
                customFee, CustomFee.PROTOBUF, com.hederahashgraph.api.proto.java.CustomFee::parseFrom);
    }

    private interface ProtoParser<R extends GeneratedMessage> {
        R parseFrom(byte[] bytes) throws InvalidProtocolBufferException;
    }

    private static <T, R extends GeneratedMessage> R explicitPbjToProto(
            @NonNull final T pbj, @NonNull final Codec<T> pbjCodec, @NonNull final ProtoParser<R> protoParser) {
        requireNonNull(pbj);
        requireNonNull(pbjCodec);
        requireNonNull(protoParser);
        try {
            return protoParser.parseFrom(asBytes(pbjCodec, pbj));
        } catch (InvalidProtocolBufferException e) {
            // Should be impossible
            throw new IllegalStateException("Serialization failure for " + pbj, e);
        }
    }

    public static @NonNull com.hederahashgraph.api.proto.java.SubType fromPbj(@NonNull SubType subType) {
        requireNonNull(subType);
        return com.hederahashgraph.api.proto.java.SubType.valueOf(subType.name());
    }

    public static @NonNull TokenID toPbj(@NonNull com.hederahashgraph.api.proto.java.TokenID tokenID) {
        requireNonNull(tokenID);
        return TokenID.newBuilder()
                .shardNum(tokenID.getShardNum())
                .realmNum(tokenID.getRealmNum())
                .tokenNum(tokenID.getTokenNum())
                .build();
    }

    public static @NonNull AccountID toPbj(@NonNull com.hederahashgraph.api.proto.java.AccountID accountID) {
        requireNonNull(accountID);
        final var builder =
                AccountID.newBuilder().shardNum(accountID.getShardNum()).realmNum(accountID.getRealmNum());
        if (accountID.getAccountCase() == AccountCase.ALIAS) {
            builder.alias(Bytes.wrap(accountID.getAlias().toByteArray()));
        } else {
            builder.accountNum(accountID.getAccountNum());
        }
        return builder.build();
    }

    public static @NonNull EntityNumber toPbj(@NonNull com.hederahashgraph.api.proto.java.EntityNumber entityNumber) {
        requireNonNull(entityNumber);
        final var builder = EntityNumber.newBuilder().number(entityNumber.getNumber());
        return builder.build();
    }

    public static <T extends GeneratedMessage, R> @NonNull R protoToPbj(
            @NonNull final T proto, @NonNull final Class<R> pbjClass) {
        try {
            final var bytes = requireNonNull(proto).toByteArray();
            final var codecField = requireNonNull(pbjClass).getDeclaredField("PROTOBUF");
            final var codec = (Codec<R>) codecField.get(null);
            return codec.parse(BufferedData.wrap(bytes));
        } catch (NoSuchFieldException | IllegalAccessException | ParseException e) {
            // Should be impossible, so just propagate an exception
            throw new RuntimeException("Invalid conversion to PBJ for " + pbjClass.getSimpleName(), e);
        }
    }

    public static @NonNull HederaFunctionality toPbj(
            @NonNull com.hederahashgraph.api.proto.java.HederaFunctionality function) {
        return HederaFunctionality.values()[requireNonNull(function).ordinal()];
    }

    public static @NonNull com.hederahashgraph.api.proto.java.HederaFunctionality fromPbj(
            @NonNull final HederaFunctionality function) {
        return com.hederahashgraph.api.proto.java.HederaFunctionality.values()[
                requireNonNull(function).ordinal()];
    }

    public static @NonNull SubType toPbj(@NonNull com.hederahashgraph.api.proto.java.SubType subType) {
        requireNonNull(subType);
        return switch (subType) {
            case DEFAULT -> SubType.DEFAULT;
            case TOKEN_FUNGIBLE_COMMON -> SubType.TOKEN_FUNGIBLE_COMMON;
            case TOKEN_NON_FUNGIBLE_UNIQUE -> SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
            case TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES -> SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
            case TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES -> SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
            case SCHEDULE_CREATE_CONTRACT_CALL -> SubType.SCHEDULE_CREATE_CONTRACT_CALL;
            case TOPIC_CREATE_WITH_CUSTOM_FEES -> SubType.TOPIC_CREATE_WITH_CUSTOM_FEES;
            case SUBMIT_MESSAGE_WITH_CUSTOM_FEES -> SubType.SUBMIT_MESSAGE_WITH_CUSTOM_FEES;
            case UNRECOGNIZED -> throw new IllegalArgumentException("Unknown subType UNRECOGNIZED");
        };
    }

    public static @NonNull ResponseCodeEnum toPbj(@NonNull com.hederahashgraph.api.proto.java.ResponseCodeEnum code) {
        return ResponseCodeEnum.values()[requireNonNull(code).ordinal()];
    }

    public static @NonNull com.hederahashgraph.api.proto.java.ContractID fromPbj(final @NonNull ContractID contractID) {
        requireNonNull(contractID);
        return com.hederahashgraph.api.proto.java.ContractID.newBuilder()
                .setRealmNum(contractID.realmNum())
                .setShardNum(contractID.shardNum())
                .setContractNum(contractID.contractNumOrElse(0L))
                .setEvmAddress(ByteString.copyFrom(asBytes(contractID.evmAddressOrElse(Bytes.EMPTY))))
                .build();
    }

    public static @NonNull ByteString fromPbj(@NonNull Bytes bytes) {
        requireNonNull(bytes);
        final byte[] data = new byte[Math.toIntExact(bytes.length())];
        bytes.getBytes(0, data);
        return ByteString.copyFrom(data);
    }

    public static com.hederahashgraph.api.proto.java.Timestamp fromPbj(@NonNull Timestamp now) {
        requireNonNull(now);
        return com.hederahashgraph.api.proto.java.Timestamp.newBuilder()
                .setSeconds(now.seconds())
                .setNanos(now.nanos())
                .build();
    }

    public static @NonNull com.hederahashgraph.api.proto.java.ScheduleInfo fromPbj(@NonNull ScheduleInfo pbjValue) {
        requireNonNull(pbjValue);
        try {
            final var bytes = asBytes(ScheduleInfo.PROTOBUF, pbjValue);
            return com.hederahashgraph.api.proto.java.ScheduleInfo.parseFrom(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static @NonNull com.hederahashgraph.api.proto.java.Transaction fromPbj(@NonNull Transaction pbjValue) {
        requireNonNull(pbjValue);
        try {
            final var bytes = asBytes(Transaction.PROTOBUF, pbjValue);
            return com.hederahashgraph.api.proto.java.Transaction.parseFrom(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static @NonNull com.hederahashgraph.api.proto.java.FeeData fromPbj(@NonNull FeeData feeData) {
        requireNonNull(feeData);
        return com.hederahashgraph.api.proto.java.FeeData.newBuilder()
                .setNodedata(fromPbj(feeData.nodedataOrElse(FeeComponents.DEFAULT)))
                .setNetworkdata(fromPbj(feeData.networkdataOrElse(FeeComponents.DEFAULT)))
                .setServicedata(fromPbj(feeData.servicedataOrElse(FeeComponents.DEFAULT)))
                .setSubTypeValue(feeData.subType().protoOrdinal())
                .build();
    }

    public static @NonNull com.hederahashgraph.api.proto.java.FeeComponents fromPbj(
            @NonNull FeeComponents feeComponents) {
        requireNonNull(feeComponents);
        return com.hederahashgraph.api.proto.java.FeeComponents.newBuilder()
                .setMin(feeComponents.min())
                .setMax(feeComponents.max())
                .setConstant(feeComponents.constant())
                .setBpt(feeComponents.bpt())
                .setVpt(feeComponents.vpt())
                .setRbh(feeComponents.rbh())
                .setSbh(feeComponents.sbh())
                .setGas(feeComponents.gas())
                .setTv(feeComponents.tv())
                .setBpr(feeComponents.bpr())
                .setSbpr(feeComponents.sbpr())
                .build();
    }

    public static FileID toPbj(com.hederahashgraph.api.proto.java.FileID fileID) {
        return protoToPbj(fileID, FileID.class);
    }

    public static @NonNull com.hederahashgraph.api.proto.java.AccountID fromPbj(@NonNull AccountID accountID) {
        requireNonNull(accountID);
        final var builder = com.hederahashgraph.api.proto.java.AccountID.newBuilder()
                .setShardNum(accountID.shardNum())
                .setRealmNum(accountID.realmNum());

        final var account = accountID.account();
        switch (account.kind()) {
            case ACCOUNT_NUM -> builder.setAccountNum(account.as());
            case ALIAS -> builder.setAlias(fromPbj((Bytes) account.as()));
            case UNSET -> throw new RuntimeException("Invalid account ID, no account type!");
        }

        return builder.build();
    }

    public static @NonNull Key toPbj(@NonNull com.hederahashgraph.api.proto.java.Key keyValue) {
        requireNonNull(keyValue);
        try {
            final var bytes = keyValue.toByteArray();
            return Key.PROTOBUF.parse(BufferedData.wrap(bytes));
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public static Timestamp toPbj(@NonNull com.hederahashgraph.api.proto.java.Timestamp t) {
        requireNonNull(t);
        return Timestamp.newBuilder()
                .seconds(t.getSeconds())
                .nanos(t.getNanos())
                .build();
    }

    public static @NonNull com.hederahashgraph.api.proto.java.ExchangeRate fromPbj(@NonNull ExchangeRate exchangeRate) {
        return com.hederahashgraph.api.proto.java.ExchangeRate.newBuilder()
                .setCentEquiv(exchangeRate.centEquiv())
                .setHbarEquiv(exchangeRate.hbarEquiv())
                .build();
    }

    public static @NonNull TransactionBody toPbj(@NonNull com.hederahashgraph.api.proto.java.TransactionBody txBody) {
        requireNonNull(txBody);
        try {
            final var bytes = txBody.toByteArray();
            return TransactionBody.PROTOBUF.parse(BufferedData.wrap(bytes));
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public static @NonNull byte[] asBytes(@NonNull BufferedData b) {
        final var buf = new byte[Math.toIntExact(b.position())];
        b.readBytes(buf);
        return buf;
    }

    public static @NonNull com.hederahashgraph.api.proto.java.SchedulableTransactionBody fromPbj(
            @NonNull SchedulableTransactionBody tx) {
        requireNonNull(tx);
        try {
            final var bytes = asBytes(SchedulableTransactionBody.PROTOBUF, tx);
            return com.hederahashgraph.api.proto.java.SchedulableTransactionBody.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts a {@link ByteString} to a {@link Bytes} object.
     * @param contents The {@link ByteString} to convert.
     * @return The {@link Bytes} object.
     */
    public static Bytes fromByteString(ByteString contents) {
        return Bytes.wrap(unwrapUnsafelyIfPossible(contents));
    }

    public static ServiceEndpoint toPbj(@NonNull com.hederahashgraph.api.proto.java.ServiceEndpoint t) {
        requireNonNull(t);
        return ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(t.getIpAddressV4().toByteArray()))
                .port(t.getPort())
                .domainName(t.getDomainName())
                .build();
    }
}
