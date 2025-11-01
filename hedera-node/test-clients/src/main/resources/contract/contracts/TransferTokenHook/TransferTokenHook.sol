// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import "./IHieroAccountAllowanceHook.sol";
import "./IHieroHook.sol";

interface IHederaTokenServiceMinimal {
    function transferToken(
        address token,
        address sender,
        address recipient,
        int64 amount
    ) external returns (int64 responseCode);
}

/// Hook that transfers fungible tokens to the hook owner during allow()
/// The EvmHookCall.data must be abi.encode(address token, address from, int64 amount)
contract TransferTokenHook is IHieroAccountAllowanceHook {
    // HIP-18 HTS precompile
    IHederaTokenServiceMinimal constant HTS = IHederaTokenServiceMinimal(address(uint160(0x167)));
    // HIP-1195 special hook address (0x...016d padded to 20 bytes)
    address constant HOOK_ADDR = address(uint160(0x16d));

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory /* proposedTransfers */
    ) external payable override returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");
        // Decode call data: token to move, source account, and amount
        (address token, address from, int64 amount) = abi.decode(context.data, (address, address, int64));
        // Move tokens from 'from' to the hook owner so they can spend in this transaction
        int64 rc = HTS.transferToken(token, from, context.owner, amount);
        require(rc == 22, "Token transfer failed"); // 22 == HederaResponseCodes.SUCCESS
        return true;
    }
}
