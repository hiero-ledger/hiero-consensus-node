// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroAccountAllowanceHook.sol';

contract OneTimeCodeHook is IHieroAccountAllowanceHook {
    /// The hash of a one-time use passcode string, at storage slot 0x00
    bytes32 passcodeHash;

    // HIP-1195 special hook address (0x...016d padded to 20 bytes)
    address constant HOOK_ADDR = address(uint160(0x16d));

    /// Allow the proposed transfers if and only if the args are the
    /// ABI encoding of the current one-time use passcode in storage.
    ///
    /// NOTE: this lambda's behavior does not depend on what owning
    /// address is set in `context.owner`; it depends only the contents
    /// of the active lambda's 0x00 storage slot.
    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external override payable returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");
        (string memory passcode) = abi.decode(context.data, (string));
        bytes32 hash = keccak256(abi.encodePacked(passcode));
        bool matches = hash == passcodeHash;
        if (matches) {
            passcodeHash = 0;
        }
        return matches;
    }
}