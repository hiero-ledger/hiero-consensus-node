pragma solidity ^0.8.0;

contract HtsInnerFrameCycle {

    address constant HTS_PRECOMPILE = address(0x167);

    function transferTokensCycle(
        uint256 tokenNum,
        uint256 accountNum,
        uint256 entryCount,
        uint256 repetitions,
        uint256 childGas
    ) external returns (uint256 successes, uint256 failures) {
        successes = 0;
        failures = 0;
        bytes memory payload = _buildTransferTokensPayload(tokenNum, accountNum, entryCount);
        address hts = address(uint160(HTS_PRECOMPILE));
        for (uint256 i = 0; i < repetitions; i++) {
            if (_callHts(hts, payload, childGas)) {successes++;} else {failures++;}
        }
    }

    function transferTokensCycleWithChildFrame(
        uint256 tokenNum,
        uint256 accountNum,
        uint256 entryCount,
        uint256 repetitions,
        uint256 childGas,
        uint256 innerChildGas
    ) external returns (uint256 successes, uint256 failures) {
        successes = 0;
        failures = 0;
        bytes memory payload = _buildTransferTokensPayload(tokenNum, accountNum, entryCount);
        bytes memory outerCall = abi.encodeWithSelector(this.innerHtsCall.selector, payload, innerChildGas);
        address self = address(this);
        for (uint256 i = 0; i < repetitions; i++) {
            bool ok;
            assembly {
                ok := call(childGas, self, 0, add(outerCall, 32), mload(outerCall), 0, 0)
            }
            if (ok) {successes++;} else {failures++;}
        }
    }

    function _buildTransferTokensPayload(
        uint256 tokenNum,
        uint256 accountNum,
        uint256 entryCount
    ) private pure returns (bytes memory payload) {
        bytes4 selector = bytes4(keccak256("transferTokens(address,address[],int64[])"));
        uint256 N = entryCount;
        uint256 size = 164 + 64 * N;
        payload = new bytes(size);                       // built IN EVM MEMORY -> top-level tx stays < 6KB
        uint256 tokenAddr = tokenNum;
        uint256 acct = accountNum;
        assembly {
            let p := add(payload, 32)
            mstore(p, selector)
            mstore(add(p, 4), tokenAddr)                 // token (static address)
            mstore(add(p, 36), 0x60)                     // offset to accounts[] = 96
            mstore(add(p, 68), add(128, mul(32, N)))     // offset to amounts[]  = 128 + 32N
            let accLen := add(p, 100)
            mstore(accLen, N)
            let accData := add(accLen, 32)
            let amtLen := add(accData, mul(32, N))
            mstore(amtLen, N)
            let amtData := add(amtLen, 32)
            for {let i := 0} lt(i, N) {i := add(i, 1)} {
                mstore(add(accData, mul(32, i)), acct)   // account address (long-zero)
                mstore(add(amtData, mul(32, i)), 1)      // amount = 1 (int64, credit branch)
            }
        }
    }

    function innerHtsCall(bytes memory payload, uint256 innerChildGas) external returns (bool) {
        return _callHts(address(uint160(HTS_PRECOMPILE)), payload, innerChildGas);
    }

    function _callHts(address hts, bytes memory payload, uint256 childGas) internal returns (bool ok) {
        assembly {
            ok := call(childGas, hts, 0, add(payload, 32), mload(payload), 0, 0)
        }
    }

}