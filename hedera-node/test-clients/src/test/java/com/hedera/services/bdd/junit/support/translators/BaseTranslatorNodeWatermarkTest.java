// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.node.app.hapi.utils.EntityType.ACCOUNT;
import static com.hedera.node.app.hapi.utils.EntityType.FILE;
import static com.hedera.node.app.hapi.utils.EntityType.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests proving the root cause of the flaky {@code TransactionRecordParityValidator}
 * failures where a successful TokenCreate inside an atomic batch was translated with a stale
 * token number in its receipt (e.g. expected 5012, got 4847).
 *
 * <p>The bug: {@link BaseTranslator#prepareForUnit} updated a single cross-type watermark with the
 * max across ALL {@code nextCreatedNums} lists, including NODE and REGISTERED_NODE. Node ids live
 * in their own tiny id space (0, 1, 2, ...), so a unit whose only creation was a node clobbered
 * the entity-num watermark down to that node id. Every pre-existing entity touched by the next
 * unit was then misclassified as created in that unit, and a TokenCreate consumed the lowest
 * (stale) token number instead of its actual one.
 *
 * <p>The fix is a per-entity-type watermark; simply excluding nodes from the cross-type watermark
 * would break detection of genesis system files, which are created after the higher-numbered
 * genesis system accounts.
 */
class BaseTranslatorNodeWatermarkTest {

    private static final long SHARD = 11L;
    private static final long REALM = 12L;

    @Test
    @DisplayName("NodeCreate does not regress the entity-num watermark")
    void nodeCreateDoesNotRegressWatermark() {
        final var translator = new BaseTranslator(SHARD, REALM);

        // A unit creates token 4847, advancing the token watermark
        translator.prepareForUnit(unitWith(tokenStateChange(4847L)));
        assertEquals(4847L, translator.nextCreatedNum(TOKEN));

        // A unit creates account 5000, advancing the account watermark
        translator.prepareForUnit(unitWith(accountStateChange(5000L)));
        assertEquals(5000L, translator.nextCreatedNum(ACCOUNT));

        // A unit whose only creation is node 14 (node ids are a separate, tiny id space)
        translator.prepareForUnit(unitWith(nodeStateChange(14L)));

        // A later unit updates pre-existing token 4847 (e.g. a mint) and creates token 5012
        translator.prepareForUnit(unitWith(tokenStateChange(4847L), tokenStateChange(5012L)));

        assertFalse(
                translator.entityCreatedThisUnit(TOKEN, 4847L),
                "Pre-existing token must not be misclassified as created after a NodeCreate");
        assertEquals(
                5012L,
                translator.nextCreatedNum(TOKEN),
                "TokenCreate must consume its actual token number, not a stale one");
    }

    @Test
    @DisplayName("Genesis system files are detected even after higher-numbered system accounts")
    void genesisSystemFilesDetectedAfterSystemAccounts() {
        final var translator = new BaseTranslator(SHARD, REALM);

        // Genesis creates system accounts up to 1000 before the system files
        translator.prepareForUnit(unitWith(accountStateChange(1000L)));
        assertEquals(1000L, translator.nextCreatedNum(ACCOUNT));

        // System file 101 must still be detected as a creation despite its lower number
        translator.prepareForUnit(unitWith(fileStateChange(101L)));
        assertEquals(101L, translator.nextCreatedNum(FILE));
    }

    private static BlockTransactionalUnit unitWith(final StateChange... changes) {
        return new BlockTransactionalUnit(List.of(), new ArrayList<>(List.of(changes)));
    }

    private static StateChange accountStateChange(final long accountNum) {
        final var accountId = AccountID.newBuilder()
                .shardNum(SHARD)
                .realmNum(REALM)
                .accountNum(accountNum)
                .build();
        return StateChange.newBuilder()
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder().accountIdKey(accountId).build())
                        .value(MapChangeValue.newBuilder()
                                .accountValue(Account.newBuilder()
                                        .accountId(accountId)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static StateChange fileStateChange(final long fileNum) {
        return StateChange.newBuilder()
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder()
                                .fileIdKey(FileID.newBuilder()
                                        .shardNum(SHARD)
                                        .realmNum(REALM)
                                        .fileNum(fileNum)
                                        .build())
                                .build())
                        .value(MapChangeValue.newBuilder()
                                .fileValue(File.newBuilder().build())
                                .build())
                        .build())
                .build();
    }

    private static StateChange nodeStateChange(final long nodeId) {
        return StateChange.newBuilder()
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder().entityNumberKey(nodeId).build())
                        .value(MapChangeValue.newBuilder()
                                .nodeValue(Node.newBuilder().nodeId(nodeId).build())
                                .build())
                        .build())
                .build();
    }

    private static StateChange tokenStateChange(final long tokenNum) {
        return StateChange.newBuilder()
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder()
                                .tokenIdKey(TokenID.newBuilder()
                                        .shardNum(SHARD)
                                        .realmNum(REALM)
                                        .tokenNum(tokenNum)
                                        .build())
                                .build())
                        .value(MapChangeValue.newBuilder()
                                .tokenValue(Token.newBuilder()
                                        .tokenType(TokenType.FUNGIBLE_COMMON)
                                        .build())
                                .build())
                        .build())
                .build();
    }
}
