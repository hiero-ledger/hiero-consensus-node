// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.asIdForKeyLookUp;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asIdWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTokenId;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.utility.CommonUtils.unhex;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.google.protobuf.UInt32Value;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.PendingAirdropId;
import com.hederahashgraph.api.proto.java.PendingAirdropRecord;
import com.hederahashgraph.api.proto.java.PendingAirdropValue;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.hiero.consensus.model.utility.CommonUtils;

public class TokenMovement {
    private final long amount;
    private final String token;
    private long[] serialNums;
    private Optional<String> sender;
    private Optional<String> receiver;
    private Optional<ByteString> evmAddressReceiver;
    private final Optional<List<String>> receivers;
    private final Optional<Function<HapiSpec, String>> senderFn;
    private final Optional<Function<HapiSpec, String>> receiverFn;
    private int expectedDecimals;
    private boolean isApproval = false;

    public static final TokenID HBAR_SENTINEL_TOKEN_ID = TokenID.getDefaultInstance();

    TokenMovement(
            String token,
            Optional<String> sender,
            long amount,
            Optional<String> receiver,
            Optional<List<String>> receivers) {
        this.token = token;
        this.sender = sender;
        this.amount = amount;
        this.receiver = receiver;
        this.receivers = receivers;

        evmAddressReceiver = Optional.empty();
        senderFn = Optional.empty();
        receiverFn = Optional.empty();
        expectedDecimals = -1;
    }

    TokenMovement(
            String token,
            Optional<String> sender,
            long amount,
            long[] serialNums,
            Optional<ByteString> evmAddressReceiver) {
        this.token = token;
        this.sender = sender;
        this.amount = amount;
        this.serialNums = serialNums;
        this.evmAddressReceiver = evmAddressReceiver;

        receiver = Optional.empty();
        receivers = Optional.empty();
        senderFn = Optional.empty();
        receiverFn = Optional.empty();
        expectedDecimals = -1;
    }

    TokenMovement(
            @NonNull final String token,
            @NonNull final Function<HapiSpec, String> senderFn,
            final long amount,
            @NonNull final Function<HapiSpec, String> receiverFn,
            @Nullable final long[] serialNums) {
        this.token = requireNonNull(token);
        this.senderFn = Optional.of(requireNonNull(senderFn));
        this.amount = amount;
        this.receiverFn = Optional.of(requireNonNull(receiverFn));

        evmAddressReceiver = Optional.empty();
        sender = Optional.empty();
        receiver = Optional.empty();
        receivers = Optional.empty();
        expectedDecimals = -1;
        this.serialNums = serialNums;
    }

    TokenMovement(
            @NonNull final String token,
            @NonNull final String sender,
            final long amount,
            @NonNull final Function<HapiSpec, String> receiverFn,
            @Nullable final long[] serialNums) {
        this.token = requireNonNull(token);
        this.senderFn = Optional.empty();
        this.amount = amount;
        this.receiverFn = Optional.of(requireNonNull(receiverFn));

        evmAddressReceiver = Optional.empty();
        this.sender = Optional.of(requireNonNull(sender));
        receiver = Optional.empty();
        receivers = Optional.empty();
        expectedDecimals = -1;
        this.serialNums = serialNums;
    }

    TokenMovement(
            @NonNull final String token,
            @NonNull final Function<HapiSpec, String> senderFn,
            final long amount,
            @NonNull final String receiver,
            @NonNull final long[] serialNums) {
        this.token = requireNonNull(token);
        this.senderFn = Optional.of(requireNonNull(senderFn));
        this.amount = amount;
        this.receiverFn = Optional.empty();

        evmAddressReceiver = Optional.empty();
        sender = Optional.empty();
        this.receiver = Optional.of(receiver);
        receivers = Optional.empty();
        expectedDecimals = -1;
        this.serialNums = serialNums;
    }

    TokenMovement(
            String token,
            Optional<String> sender,
            long amount,
            long[] serialNums,
            Optional<String> receiver,
            Optional<List<String>> receivers,
            boolean isApproval) {
        this.token = token;
        this.sender = sender;
        this.amount = amount;
        this.serialNums = serialNums;
        this.receiver = receiver;
        this.receivers = receivers;
        this.isApproval = isApproval;

        evmAddressReceiver = Optional.empty();
        senderFn = Optional.empty();
        receiverFn = Optional.empty();
        expectedDecimals = -1;
    }

    TokenMovement(
            String token,
            Optional<String> sender,
            long amount,
            Optional<String> receiver,
            Optional<List<String>> receivers,
            int expectedDecimals,
            boolean isApproval) {
        this.token = token;
        this.sender = sender;
        this.amount = amount;
        this.receiver = receiver;
        this.receivers = receivers;
        this.expectedDecimals = expectedDecimals;
        this.isApproval = isApproval;

        evmAddressReceiver = Optional.empty();
        senderFn = Optional.empty();
        receiverFn = Optional.empty();
    }

    public String getToken() {
        return token;
    }

    /**
     *  Try to identify any Token -> Receiver in this movement that has no relations.
     *  It is used when we try to estimate association fees, that should be prepaid while sending airdrop transactions.
     *
     * @return map Token to list of receivers accounts
     */
    public List<String> getAccountsWithMissingRelations(HapiSpec spec) {
        var accountsWithoutRel = new ArrayList<String>();
        if (receiver.isPresent() && !spec.registry().hasTokenRel(receiver.get(), token)) {
            accountsWithoutRel.add(receiver.get());
        }
        receivers.ifPresent(strings -> strings.forEach(receiver -> {
            if (!spec.registry().hasTokenRel(receiver, token)) {
                accountsWithoutRel.add(receiver);
            }
        }));
        // check if receiver is evm address
        if (accountsWithoutRel.isEmpty() && evmAddressReceiver.isPresent()) {
            accountsWithoutRel.add(CommonUtils.hex(evmAddressReceiver.get().toByteArray()));
        }

        return accountsWithoutRel;
    }

    public boolean isTrulyToken() {
        return token != null && !token.equals(HapiSuite.HBAR_TOKEN_SENTINEL);
    }

    public boolean isFungibleToken() {
        return serialNums == null;
    }

    public List<Map.Entry<String, Long>> generallyInvolved() {
        if (sender.isPresent()) {
            Map.Entry<String, Long> senderEntry = new AbstractMap.SimpleEntry<>(token + "|" + sender.get(), -amount);
            if (receiver.isPresent()) {
                return List.of(senderEntry, new AbstractMap.SimpleEntry<>(token + "|" + receiver.get(), +amount));
            }

            return (receivers.isPresent() ? involvedInDistribution(senderEntry) : List.of(senderEntry));
        }
        return Collections.emptyList();
    }

    private List<Map.Entry<String, Long>> involvedInDistribution(Map.Entry<String, Long> senderEntry) {
        List<Map.Entry<String, Long>> all = new ArrayList<>();
        all.add(senderEntry);
        var targets = receivers.get();
        var perTarget = senderEntry.getValue() / targets.size();
        for (String target : targets) {
            all.add(new AbstractMap.SimpleEntry<>(target, perTarget));
        }
        return all;
    }

    public TokenTransferList specializedFor(HapiSpec spec) {
        var scopedTransfers = TokenTransferList.newBuilder();
        var id = isTrulyToken() ? asTokenId(token, spec) : HBAR_SENTINEL_TOKEN_ID;
        scopedTransfers.setToken(id);
        if (senderFn.isPresent()) {
            var specialSender = senderFn.get().apply(spec);
            sender = Optional.of(specialSender);
            scopedTransfers.addTransfers(adjustment(specialSender, -amount, spec));
        } else if (sender.isPresent()) {
            scopedTransfers.addTransfers(adjustment(sender.get(), -amount, spec));
        }
        if (receiverFn.isPresent()) {
            var specialReceiver = receiverFn.get().apply(spec);
            receiver = Optional.of(specialReceiver);
            scopedTransfers.addTransfers(adjustment(specialReceiver, +amount, spec));
        } else if (receiver.isPresent()) {
            scopedTransfers.addTransfers(adjustment(receiver.get(), +amount, spec));
        } else if (evmAddressReceiver.isPresent()) {
            scopedTransfers.addTransfers(adjustment(evmAddressReceiver.get(), +amount));
        } else if (receivers.isPresent()) {
            var targets = receivers.get();
            var amountPerReceiver = amount / targets.size();
            for (int i = 0, n = targets.size(); i < n; i++) {
                scopedTransfers.addTransfers(adjustment(targets.get(i), +amountPerReceiver, spec));
            }
        }
        if (expectedDecimals > 0) {
            scopedTransfers.setExpectedDecimals(UInt32Value.of(expectedDecimals));
        }
        return scopedTransfers.build();
    }

    public TokenTransferList specializedForNft(HapiSpec spec) {
        var scopedTransfers = TokenTransferList.newBuilder();
        var id = isTrulyToken() ? asTokenId(token, spec) : HBAR_SENTINEL_TOKEN_ID;
        scopedTransfers.setToken(id);
        if (senderFn.isPresent()) {
            final var source = senderFn.get().apply(spec);
            if (receiver.isPresent()) {
                for (long serialNum : serialNums) {
                    scopedTransfers.addNftTransfers(adjustment(source, receiver.get(), serialNum, spec));
                }
            } else if (receiverFn.isPresent()) {
                final var dest = receiverFn.get().apply(spec);
                for (long serialNum : serialNums) {
                    scopedTransfers.addNftTransfers(adjustment(source, dest, serialNum, spec));
                }
            }
        } else if (sender.isPresent() && evmAddressReceiver.isEmpty()) {
            if (receiver.isPresent()) {
                for (long serialNum : serialNums) {
                    scopedTransfers.addNftTransfers(adjustment(sender.get(), receiver.get(), serialNum, spec));
                }
            } else if (receiverFn.isPresent()) {
                final var dest = receiverFn.get().apply(spec);
                for (long serialNum : serialNums) {
                    scopedTransfers.addNftTransfers(adjustment(sender.get(), dest, serialNum, spec));
                }
            }
        } else if (sender.isPresent()) {
            for (long serialNum : serialNums) {
                scopedTransfers.addNftTransfers(adjustment(sender.get(), evmAddressReceiver.get(), serialNum, spec));
            }
        }

        return scopedTransfers.build();
    }

    public List<PendingAirdropRecord> specializedForPendingAirdrop(HapiSpec spec) {
        List<PendingAirdropRecord> records = new ArrayList<>();
        var tokenXfer = specializedFor(spec);
        var aaSender = tokenXfer.getTransfersList().stream()
                .filter(item -> item.getAmount() < 0)
                .findFirst();
        tokenXfer.getTransfersList().stream()
                .filter(item -> item.getAmount() >= 0)
                .forEach(transfer -> {
                    var tokenId = tokenXfer.getToken();
                    var pendingAirdropId = PendingAirdropId.newBuilder()
                            .setSenderId(aaSender.orElseThrow().getAccountID())
                            .setReceiverId(transfer.getAccountID())
                            .setFungibleTokenType(tokenId)
                            .build();
                    var pendingAirdropValue = PendingAirdropValue.newBuilder()
                            .setAmount(transfer.getAmount())
                            .build();
                    var pendingAirdropRecord = PendingAirdropRecord.newBuilder()
                            .setPendingAirdropId(pendingAirdropId)
                            .setPendingAirdropValue(pendingAirdropValue)
                            .build();
                    records.add(pendingAirdropRecord);
                });

        return records;
    }

    public List<PendingAirdropRecord> specializedForNftPendingAirdop(HapiSpec spec) {
        List<PendingAirdropRecord> records = new ArrayList<>();
        var tokenXfer = specializedForNft(spec);
        var tokenId = tokenXfer.getToken();
        tokenXfer.getNftTransfersList().stream().forEach(transfer -> {
            var aaSender = transfer.getSenderAccountID();
            var aaReceiver = transfer.getReceiverAccountID();
            var pendingAirdropId = PendingAirdropId.newBuilder()
                    .setSenderId(aaSender)
                    .setReceiverId(aaReceiver)
                    .setNonFungibleToken(NftID.newBuilder()
                            .setTokenID(tokenId)
                            .setSerialNumber(tokenXfer.getNftTransfers(0).getSerialNumber()))
                    .build();
            var pendingAirdropRecord = PendingAirdropRecord.newBuilder()
                    .setPendingAirdropId(pendingAirdropId)
                    .build();

            records.add(pendingAirdropRecord);
        });
        return records;
    }

    private AccountAmount adjustment(String name, long value, HapiSpec spec) {
        return AccountAmount.newBuilder()
                .setAccountID(asIdForKeyLookUp(name, spec))
                .setAmount(value)
                .setIsApproval(isApproval)
                .build();
    }

    private AccountAmount adjustment(ByteString evmAddress, long value) {
        return AccountAmount.newBuilder()
                .setAccountID(asIdWithAlias(evmAddress))
                .setAmount(value)
                .setIsApproval(isApproval)
                .build();
    }

    private NftTransfer adjustment(String senderName, String receiverName, long value, HapiSpec spec) {
        return NftTransfer.newBuilder()
                .setSenderAccountID(asIdForKeyLookUp(senderName, spec))
                .setReceiverAccountID(asIdForKeyLookUp(receiverName, spec))
                .setSerialNumber(value)
                .setIsApproval(isApproval)
                .build();
    }

    private NftTransfer adjustment(String senderName, ByteString evmAddress, long value, HapiSpec spec) {
        return NftTransfer.newBuilder()
                .setSenderAccountID(asIdForKeyLookUp(senderName, spec))
                .setReceiverAccountID(asIdWithAlias(evmAddress))
                .setSerialNumber(value)
                .setIsApproval(isApproval)
                .build();
    }

    public static class Builder {
        private final long amount;
        private long[] serialNums;
        private final String token;
        private int expectedDecimals;
        private boolean isAllowance = false;

        public Builder(long amount, String token, boolean isAllowance) {
            this.token = token;
            this.amount = amount;
            this.isAllowance = isAllowance;
        }

        public Builder(long amount, String token) {
            this.token = token;
            this.amount = amount;
        }

        public Builder(long amount, String token, int expectedDecimals) {
            this.token = token;
            this.amount = amount;
            this.expectedDecimals = expectedDecimals;
        }

        public Builder(long amount, String token, long... serialNums) {
            this.amount = amount;
            this.token = token;
            this.serialNums = serialNums;
        }

        public Builder(long amount, String token, boolean isAllowance, long... serialNums) {
            this.amount = amount;
            this.token = token;
            this.isAllowance = isAllowance;
            this.serialNums = serialNums;
        }

        public TokenMovement between(String sender, String receiver) {
            return new TokenMovement(
                    token,
                    Optional.of(sender),
                    amount,
                    serialNums,
                    Optional.of(receiver),
                    Optional.empty(),
                    isAllowance);
        }

        public TokenMovement between(String sender, ByteString receiver) {
            return new TokenMovement(token, Optional.of(sender), amount, serialNums, Optional.of(receiver));
        }

        public TokenMovement between(String sender, Address to) {
            return new TokenMovement(
                    token,
                    Optional.of(sender),
                    amount,
                    serialNums,
                    Optional.of(ByteString.copyFrom(unhex(to.toString().substring(2)))));
        }

        public TokenMovement betweenWithDecimals(String sender, String receiver) {
            return new TokenMovement(
                    token,
                    Optional.of(sender),
                    amount,
                    Optional.of(receiver),
                    Optional.empty(),
                    expectedDecimals,
                    isAllowance);
        }

        public TokenMovement between(Function<HapiSpec, String> senderFn, Function<HapiSpec, String> receiverFn) {
            return new TokenMovement(token, senderFn, amount, receiverFn, serialNums);
        }

        public TokenMovement between(String sender, Function<HapiSpec, String> receiverFn) {
            return new TokenMovement(token, sender, amount, receiverFn, serialNums);
        }

        public TokenMovement between(Function<HapiSpec, String> senderFn, String receiver) {
            return new TokenMovement(token, senderFn, amount, receiver, serialNums);
        }

        public TokenMovement distributing(String sender, String... receivers) {
            return new TokenMovement(
                    token, Optional.of(sender), amount, Optional.empty(), Optional.of(List.of(receivers)));
        }

        public TokenMovement from(String magician) {
            return new TokenMovement(token, Optional.of(magician), amount, Optional.empty(), Optional.empty());
        }

        public TokenMovement to(String receiver) {
            return new TokenMovement(token, Optional.empty(), amount, Optional.of(receiver), Optional.empty());
        }

        public TokenMovement empty() {
            return new TokenMovement(token, Optional.empty(), amount, Optional.empty(), Optional.empty());
        }
    }

    public static Builder moving(long amount, String token) {
        return new Builder(amount, token);
    }

    public static Builder movingWithAllowance(long amount, String token) {
        return new Builder(amount, token, true);
    }

    public static Builder movingWithDecimals(long amount, String token, int expectedDecimals) {
        return new Builder(amount, token, expectedDecimals);
    }

    public static Builder movingUnique(String token, long... serialNums) {
        return new Builder(1, token, serialNums);
    }

    public static Builder movingUniqueWithAllowance(String token, long... serialNums) {
        return new Builder(1, token, true, serialNums);
    }

    public static Builder movingHbar(long amount) {
        return new Builder(amount, HapiSuite.HBAR_TOKEN_SENTINEL);
    }

    public static Builder movingHbarWithAllowance(long amount) {
        return new Builder(amount, HapiSuite.HBAR_TOKEN_SENTINEL, true);
    }
}
