// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroAccountAllowanceHook.sol';

interface IEmitSenderOrigin {
    function logNow() external;
}

/// A hook that calls the logNow() method on an EmitSenderOrigin contract
/// whose address is decoded from the context.data field.
contract AddressLogsHook is IHieroAccountAllowanceHook {
    // HIP-1195 special hook address (0x...016d padded to 20 bytes)
    address constant HOOK_ADDR = address(uint160(0x16d));

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external override payable returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");

        // Decode the EmitSenderOrigin contract address from context.data
        // ABI-encoded address is 32 bytes (left-padded with zeros)
        require(context.data.length >= 32, "Data must contain an ABI-encoded address");
        address emitSenderOriginAddr = abi.decode(context.data, (address));

        // Call logNow() on the EmitSenderOrigin contract
        IEmitSenderOrigin(emitSenderOriginAddr).logNow();

        // Always allow the transfer
        return true;
    }
}

