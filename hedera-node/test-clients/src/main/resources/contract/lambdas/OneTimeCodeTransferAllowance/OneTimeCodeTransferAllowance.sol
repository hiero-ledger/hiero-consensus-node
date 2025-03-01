// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.5.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./IHieroTransferAllowance.sol";

contract OneTimeCodeTransferAllowance is IHieroTransferAllowance {
    /// The hash of a one-time use passcode string
    bytes32 passcodeHash;

    /// Allow the proposed transfers if and only if the args are the
    /// ABI encoding of the current one-time use passcode in storage.
    function allow(
        IHieroTransferAllowance.ProposedTransfers memory,
        bytes memory args
    ) external override payable returns (bool) {
        (string memory passcode) = abi.decode(args, (string));
        bytes32 hash = keccak256(abi.encodePacked(passcode));
        bool matches = hash == passcodeHash;
        if (matches) {
            passcodeHash = 0;
        }
        return matches;
    }
}

