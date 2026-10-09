// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.node.config.types.StreamMode.BOTH;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.hedera.services.bdd.junit.TestTags.BLOCK_HASHING;
import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.SHARED_NETWORK;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiPrng;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassFrom;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.stream.Stream;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Checks block hashing under the network's configured {@code blockStream.digestType}, and that the hashes outside
 * the block root tree stay SHA-384 whatever that setting is.
 *
 * <p>Every assertion follows the configured digest type, so the class runs both in {@code hapiTestBlockHashingSha256}
 * (with {@code SHA_256}) and with the default {@code SHA_384}. In both, the end-of-task stream validators then
 * re-derive every block's root hash, proof and state changes with the same digest type.
 */
@Tag(BLOCK_HASHING)
public class BlockHashingDigestTypeTest {
    private static final String DIGEST_TYPE_PROPERTY = "blockStream.digestType";
    private static final String STREAM_MODE_PROPERTY = "blockStream.streamMode";
    private static final Duration BLOCKS_TIMEOUT = Duration.ofMinutes(2);
    private static final int RECORD_STREAM_HASH_LENGTH = DigestType.SHA_384.digestLength();
    private static final long TOPIC_RUNNING_HASH_VERSION = 3L;

    @HapiTest
    final Stream<DynamicTest> blockHeadersAndFootersFollowConfiguredDigestType() {
        assumeFalse(targetStreamMode() == RECORDS, "No block stream to check when streamMode=RECORDS");
        return hapiTest(
                streamMustIncludePassFrom(
                        spec -> new BlockDigestTypeAssertion(configuredDigestType(spec)), BLOCKS_TIMEOUT),
                cryptoCreate("blockHashingAccount"));
    }

    @HapiTest
    final Stream<DynamicTest> topicRunningHashStaysSha384() {
        return hapiTest(
                createTopic("blockHashingTopic"),
                submitMessageTo("blockHashingTopic").message("hello").via("submitToTopic"),
                getTxnRecord("submitToTopic").exposingTo(record -> {
                    final var receipt = record.getReceipt();
                    assertEquals(
                            RECORD_STREAM_HASH_LENGTH,
                            receipt.getTopicRunningHash().size(),
                            "Topic running hash must stay SHA-384");
                    assertEquals(TOPIC_RUNNING_HASH_VERSION, receipt.getTopicRunningHashVersion());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> utilPrngBytesStay384Bits() {
        // HIP-351 specifies 384 pseudorandom bits; hasOnlyPseudoRandomBytes() asserts that length
        return hapiTest(
                hapiPrng().via("blockHashingPrng"),
                getTxnRecord("blockHashingPrng").hasOnlyPseudoRandomBytes());
    }

    @HapiTest
    final Stream<DynamicTest> mixedTransactionsProduceValidBlocks() {
        // Varied block items for the end-of-task validators to re-hash with the configured digest type
        return hapiTest(
                cryptoCreate("mixedSender").balance(1_000_000_000L),
                cryptoCreate("mixedReceiver").balance(0L),
                cryptoTransfer(tinyBarsFromTo("mixedSender", "mixedReceiver", 1_000L)),
                tokenCreate("mixedToken").treasury("mixedSender").initialSupply(1_000L),
                tokenAssociate("mixedReceiver", "mixedToken"),
                cryptoTransfer(moving(10L, "mixedToken").between("mixedSender", "mixedReceiver")),
                fileCreate("mixedFile").contents("block hashing"),
                scheduleCreate("mixedSchedule", cryptoTransfer(tinyBarsFromTo("mixedSender", "mixedReceiver", 1L))));
    }

    private static DigestType configuredDigestType(@NonNull final HapiSpec spec) {
        final var name = spec.startupProperties().get(DIGEST_TYPE_PROPERTY);
        return name == null ? DigestType.SHA_384 : DigestType.valueOf(name);
    }

    private static StreamMode targetStreamMode() {
        final var network = SHARED_NETWORK.get();
        return network == null ? BOTH : network.startupProperties().getStreamMode(STREAM_MODE_PROPERTY);
    }

    /**
     * Passes once a few blocks have shown a header declaring the configured algorithm and a footer whose
     * block-tree hashes have that algorithm's length; fails on the first block that does not.
     */
    private static final class BlockDigestTypeAssertion implements BlockStreamAssertion {
        private static final int BLOCKS_TO_CHECK = 3;

        private final DigestType digestType;
        private final BlockHashAlgorithm expectedAlgorithm;
        private int blocksChecked;

        private BlockDigestTypeAssertion(@NonNull final DigestType digestType) {
            this.digestType = requireNonNull(digestType);
            this.expectedAlgorithm =
                    digestType == DigestType.SHA_256 ? BlockHashAlgorithm.SHA2_256 : BlockHashAlgorithm.SHA2_384;
        }

        @Override
        public boolean test(@NonNull final Block block) throws AssertionError {
            long blockNumber = -1;
            boolean sawHeader = false;
            boolean sawFooter = false;
            for (final var item : block.items()) {
                if (item.hasBlockHeader()) {
                    final var header = item.blockHeaderOrThrow();
                    blockNumber = header.number();
                    if (header.hashAlgorithm() != expectedAlgorithm) {
                        throw new AssertionError("Block #" + blockNumber + " declares " + header.hashAlgorithm()
                                + " but blockStream.digestType is " + digestType);
                    }
                    sawHeader = true;
                } else if (item.hasBlockFooter()) {
                    final var footer = item.blockFooterOrThrow();
                    assertHashLength(blockNumber, "previousBlockRootHash", footer.previousBlockRootHash());
                    assertHashLength(
                            blockNumber, "rootHashOfAllBlockHashesTree", footer.rootHashOfAllBlockHashesTree());
                    sawFooter = true;
                }
            }
            if (sawHeader && sawFooter) {
                blocksChecked++;
            }
            return blocksChecked >= BLOCKS_TO_CHECK;
        }

        private void assertHashLength(final long blockNumber, @NonNull final String field, @NonNull final Bytes hash) {
            if (hash.length() != digestType.digestLength()) {
                throw new AssertionError("Block #" + blockNumber + " footer " + field + " is " + hash.length()
                        + " bytes, expected " + digestType.digestLength() + " for " + digestType);
            }
        }

        @Override
        public String toString() {
            return "BlockDigestType(" + digestType + ")";
        }
    }
}
