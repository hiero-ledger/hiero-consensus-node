// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import "./IHieroAccountAllowanceHook.sol";
import "./IHieroHook.sol";

/**
A universal contract for testing EIP-7702 / HIP-1340 code delegations.
Note that it can take the role of being either the delegation target of another EOA
or act as a standalone contract (that itself calls other accounts), as well as acting as a hook,
depending on the needs of the given test case.
*/
contract CodeDelegationContract is IHieroAccountAllowanceHook {

    address constant HOOK_ADDR = address(uint160(0x16d));

    event HookExecuted(
        address sender,
        address thisAddress
    );

    event FallbackCalled(
        address sender,
        address thisAddress,
        uint256 value,
        bytes data
    );

    event MethodCalled(
        address sender,
        address thisAddress,
        uint256 value,
        uint256 param
    );

    uint256 private storedValue;

    fallback() external payable {
        emit FallbackCalled(msg.sender, address(this), msg.value, msg.data);
    }

    // Hook interface
    function allow(
            IHieroHook.HookContext calldata context,
            ProposedTransfers memory /* proposedTransfers */
        ) external payable override returns (bool) {
        emit HookExecuted(msg.sender, address(this));

        (address callTarget, bytes memory callData) = abi.decode(context.data, (address, bytes));

        callTarget.call(callData);

        return true;
    }

    function storeAndEmit(uint256 param) external payable {
        storedValue = param;
        emit MethodCalled(msg.sender, address(this), msg.value, param);
    }

    function getValue() public view returns (uint256) {
        return storedValue;
    }

    function executeCall(address to, uint256 value, bytes memory data) public {
        assembly {
            let result := call(gas(), to, value, add(data, 0x20), mload(data), 0, 0)

            switch result case 0 {
                let size := returndatasize()
                let ptr := mload(0x40)
                returndatacopy(ptr, 0, size)
                revert(ptr, size)
            }
            default {}
        }
    }
}
