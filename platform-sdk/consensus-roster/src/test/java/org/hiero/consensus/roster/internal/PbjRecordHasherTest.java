// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster.internal;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PbjRecordHasherTest {
    @Test
    void testHash() {
        final PbjRecordHasher hasher = new PbjRecordHasher(DigestType.SHA_384);

        final Hash hash = hasher.hash(Roster.DEFAULT, Roster.PROTOBUF);
        Assertions.assertEquals(
                "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b",
                hash.toString());

        final Hash anotherHash = hasher.hash(
                Roster.DEFAULT.copyBuilder().rosterEntries(RosterEntry.DEFAULT).build(), Roster.PROTOBUF);
        Assertions.assertEquals(
                "5d693ce2c5d445194faee6054b4d8fe4a4adc1225cf0afc2ecd7866ea895a0093ea3037951b75ab7340b75699aa1db1d",
                anotherHash.toString());
    }

    @Test
    void testHashWithSha256() {
        final PbjRecordHasher hasher = new PbjRecordHasher(DigestType.SHA_256);
        final Hash hash = hasher.hash(Roster.DEFAULT, Roster.PROTOBUF);
        Assertions.assertEquals(DigestType.SHA_256, hash.getDigestType());
        Assertions.assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash.toString());

        final Hash anotherHash = hasher.hash(
                Roster.DEFAULT.copyBuilder().rosterEntries(RosterEntry.DEFAULT).build(), Roster.PROTOBUF);
        Assertions.assertEquals(
                "102b51b9765a56a3e899f7cf0ee38e5251f9c503b357b330a49183eb7b155604", anotherHash.toString());
    }

    @Test
    void testDefaultHasherUsesThePlatformDefaultDigest() {
        final Hash hash = new PbjRecordHasher().hash(Roster.DEFAULT, Roster.PROTOBUF);
        Assertions.assertEquals(Cryptography.DEFAULT_DIGEST_TYPE, hash.getDigestType());
    }
}
