// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.files;

import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class RandomFile implements OpProvider {
    public static final int DEFAULT_CEILING_NUM = 100;

    private int ceilingNum = DEFAULT_CEILING_NUM;
    private static final int BYTES_1K = 1024;
    private static final int BYTES_2K = 2 * BYTES_1K;
    private static final int BYTES_4K = 2 * BYTES_2K;

    private final AtomicInteger opNo = new AtomicInteger();
    private final RegistrySourcedNameProvider<FileID> files;
    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(INVALID_FILE_ID);
    static byte[][] contentChoices = new byte[][] {
        paddedTo(BYTES_1K, "I turn the page and read / I dream of silent verses...".getBytes()),
        paddedTo(BYTES_2K, "In Manchua territory half is slough / Half pine tree forest...".getBytes()),
        paddedTo(BYTES_4K, "Twas brillig, and the slithy toves / Did gyre and gimble in the wabe...".getBytes()),
    };

    public RandomFile(RegistrySourcedNameProvider<FileID> files) {
        this.files = files;
    }

    public RandomFile ceiling(int n) {
        ceilingNum = n;
        return this;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        if (files.numPresent() >= ceilingNum) {
            return Optional.empty();
        }

        int n = opNo.getAndIncrement();
        final String tentativeFile = my("file" + n);
        var op = fileCreate(tentativeFile)
                .key(String.format("WACL-%d", (n % 5) + 1))
                .contents(contentChoices[n % contentChoices.length])
                .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                .hasKnownStatusFrom(permissibleOutcomes);

        return Optional.of(op);
    }

    @Override
    public List<SpecOperation> suggestedInitializers() {
        return List.of(
                newKeyNamed("WACL-1").shape(listOf(1)),
                newKeyNamed("WACL-2").shape(listOf(2)),
                newKeyNamed("WACL-3").shape(listOf(3)),
                newKeyNamed("WACL-4").shape(listOf(4)),
                newKeyNamed("WACL-5").shape(listOf(5)));
    }

    private static byte[] paddedTo(int n, byte[] bytes) {
        var filler = "abcdefghijklmnopqrstuvwxyz";

        if (bytes.length < n) {
            byte[] padded = Arrays.copyOf(bytes, n);
            for (int i = bytes.length; i < n; i++) {
                padded[i] = (byte) filler.charAt(i % filler.length());
            }
            return padded;
        } else {
            return bytes;
        }
    }

    private String my(String opName) {
        return unique(opName, RandomFile.class);
    }
}
