// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "solidity/IHieroAccountAllowanceHook.sol";
import "solidity/IHieroHook.sol";
import "solidity/IERC20.sol";

contract TinyCreate {
    function ping() external pure returns (uint256) { return 1; }
}

contract CreateOpHook is IHieroAccountAllowanceHook {
    address constant HOOK_ADDR = address(uint160(0x16d));
    event Created(address child);

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable override returns (bool) {
        require(address(this) == HOOK_ADDR, "Only callable as a hook");
        Child c = new Child(context.owner);
        emit Created(address(c));
        return true;
    }
}
contract Child {
    address public owner;
    event Alive(address who);

    constructor(address _owner) payable {
        owner = _owner;
        emit Alive(address(this));
    }

    function onlyOwner() external view {
        require(msg.sender == owner, "Only owner can call this function");
    }
}

