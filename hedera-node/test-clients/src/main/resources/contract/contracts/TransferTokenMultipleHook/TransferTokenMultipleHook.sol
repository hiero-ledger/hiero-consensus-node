// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import "../IHieroAccountAllowanceHook.sol";
import "../IHieroHook.sol";

interface IHederaTokenServiceForMultipleHook {
    function transferToken(
        address token,
        address sender,
        address recipient,
        int64 amount
    ) external returns (int64 responseCode);
}

/// Hook that performs 3 separate fungible token transfers during allow()
/// The EvmHookCall.data must be abi.encode(address token, address receiver1, address receiver2, address receiver3, int64 transferAmount)
contract TransferTokenMultipleHook is IHieroAccountAllowanceHook {
    // HIP-18 HTS precompile
    IHederaTokenServiceForMultipleHook constant HTS = IHederaTokenServiceForMultipleHook(address(uint160(0x167)));
    // HIP-1195 special hook address (0x...016d padded to 20 bytes)
    address constant HOOK_ADDR = address(uint160(0x16d));

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory /* proposedTransfers */
    ) external payable override returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");

        // Decode call data: token, 3 receivers, transfer amount per receiver
        (address token, address receiver1, address receiver2, address receiver3, int64 transferAmount) =
            abi.decode(context.data, (address, address, address, address, int64));

        // Transfer 1: owner -> receiver1
        int64 rc = HTS.transferToken(token, context.owner, receiver1, transferAmount);
        require(rc == 22, "Transfer 1 failed"); // 22 == HederaResponseCodes.SUCCESS

        // Transfer 2: owner -> receiver2
        rc = HTS.transferToken(token, context.owner, receiver2, transferAmount);
        require(rc == 22, "Transfer 2 failed");

        // Transfer 3: owner -> receiver3
        rc = HTS.transferToken(token, context.owner, receiver3, transferAmount);
        require(rc == 22, "Transfer 3 failed");

        return true;
    }
}
