// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.0 <0.9.0;
// pragma experimental ABIEncoderV2; // not needed on >=0.8.x

import "./IHieroAccountAllowanceHook.sol";
import "./IHieroHook.sol";

interface IHederaTokenServiceMinimal {
    function associateToken(address account, address token) external returns (int64);
}

contract StaticCallHook is IHieroAccountAllowanceHook {
    address constant HOOK_ADDR = address(uint160(0x16d));
    IHederaTokenServiceMinimal constant HTS =
    IHederaTokenServiceMinimal(address(uint160(0x167)));

    mapping(address => bool) public isAllowedSender;

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable override returns (bool) {
        require(address(this) == HOOK_ADDR, "Only callable as a hook");
        if (!isAllowedSender[msg.sender]) return false;

        address owner = context.owner;
        _exerciseStaticCall(owner, proposedTransfers.direct.tokens);
        _exerciseStaticCall(owner, proposedTransfers.customFee.tokens);

        // If we got here, all staticcalls succeeded
        return true;
    }

    function _exerciseStaticCall(
        address owner,
        IHieroAccountAllowanceHook.TokenTransferList[] memory lists
    ) internal {
        for (uint256 i = 0; i < lists.length; i++) {
            address token = lists[i].token;
            bytes memory payload = abi.encodeWithSelector(this._associateImpl.selector, owner, token);
            (bool ok, bytes memory ret) = address(this).staticcall(payload);
            // The inner call to HTS.associateToken() will fail b/c it's in static frame
            // So this will never actually be okay, and we will revert next line
            require(ok);
        }
    }

    function _associateImpl(address owner, address token)
    external
    payable
    returns (int64 response, address seenThis, address seenSender)
    {
        // This is a state-changing call; under STATICCALL the outer call will fail.
        response = HTS.associateToken(owner, token);
        // Assert the association succeeded (response codes are ResponseCodeEnum proto ordinals, SUCCESS=22)
        require(response == 22);
        seenThis = address(this);
        seenSender = msg.sender;
    }
}