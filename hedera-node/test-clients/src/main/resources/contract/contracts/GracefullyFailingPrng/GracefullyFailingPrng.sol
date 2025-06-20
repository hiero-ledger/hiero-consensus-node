// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;

import "../PrngSystemContract/PrngSystemContract.sol";

contract GracefullyFailingPrng is PrngSystemContract {
    function performNonExistingServiceFunctionCall() public {
        (bool success, bytes memory result) = PRECOMPILE_ADDRESS.delegatecall(abi.encodeWithSelector(FakePrngSystemContract.fakeFunction.selector));
        require(success);
    }

    function performLessThanFourBytesFunctionCall() public {
        (bool success, bytes memory result) = PRECOMPILE_ADDRESS.delegatecall(abi.encode("0xcdcd"));
        require(success);
    }
}

interface FakePrngSystemContract {
    function fakeFunction() external;
}
