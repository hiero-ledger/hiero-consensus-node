// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.impl;

import com.swirlds.base.state.Mutable;
import com.swirlds.common.Reservable;
import com.swirlds.common.merkle.interfaces.HasMerkleRoute;
import com.swirlds.common.merkle.interfaces.MerkleParent;
import com.swirlds.common.merkle.interfaces.MerkleType;
import org.hiero.base.crypto.Hashable;

public interface PartialMerkleInternal
        extends Hashable, HasMerkleRoute, Mutable, MerkleType, MerkleParent, Reservable {}
