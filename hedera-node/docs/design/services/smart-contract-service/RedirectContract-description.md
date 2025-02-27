# The "Redirect" Contract Template for proxying calls to Tokens, Accounts, and Scheduled Transactions

The "redirect" contracts (aka proxy contracts, aka facade contracts) implement the feature where you
can call a Hedera entity e.g. token, account or schedule (as of 0.59) directly at their address and they _act_
as if you made a contract call with the corresponding target address as the target parameter.

* There are three terms currently in use for the same concept: "facade", "redirect", and "proxy".
  All three terms appear in symbols in the code, at this time.  Perhaps we'll standardize on one
  of those terms and have a teeny refactoring sometime in the future.  But for the purposes of this
  document: They're synonyms used interchangeably.

There is one redirect contract which is parameterized as a _template_ with the addresses of the
HTS, HAS, and HSS system contracts, and with the correct "redirect" method selector.

When a call is made to a token, an account, or a schedule, the redirect template is
instantiated with the correct system contract address and selector, and then executed.  It wraps
the calldata (provided by the calling contract or transaction) and delegate-calls the actual
system contract, passing back whatever the return status and value is.

Expanding the template is done in
`DispatchingEvmFrameState.get{Token,Account,Schedule}RedirectCode()`.

## Raw Bytecode

Derived from [`DispatchingEvmFrameState` at `e70582e28e`](https://github.com/hashgraph/hedera-services/blob/f023153207482730f79fd5da69c6fbd29eaa3fcc/hedera-node/hedera-evm/src/main/java/com/hedera/node/app/service/evm/store/contracts/HederaEvmWorldStateTokenAccount.java#L35) (as viewed on 2024-12-08)

```
6080604052348015600f57600080fd5b50600061RRRR905077SSSSSSSSfefefe
fefefefefefefefefefefefefefefefefe600052366000602037600080366018
016008845af43d806000803e8160008114605857816000f35b816000fdfea264
6970667358221220d8378feed472ba49a0005514ef7087017f707b45fb9bf56b
b81bb93ff19a238b64736f6c634300080b0033
```

Where:

* `RRRR` will be replaced by the (2 byte) precompile contract address

  * `0167`: HTS ([HIP-218](https://hips.hedera.com/hip/hip-218))

  * `016a`: HAS ([HIP-906](https://hips.hedera.com/hip/hip-906))

  * `016b`: HSS ([HIP-755](https://hips.hedera.com/hip/hip-755))

* `SSSSSSSS` will be replaced by the (4 byte) redirect method selector

  * `618dc65e`: `redirectForToken`

  * `e4cbd3a7`: `redirectForAddress`

  * `5c3889ca`: `redirectForScheduleTxn`

* `fefefefefefefefefefefefefefefefefefefefe` will be replaced by the (20 byte) EVM address of the token
  being redirected, i.e., the HTS token kind's account-num alias (aka: long-zero).

(The templating in the source code isn't exactly organized this way, but this is the effect.)

## Solidity

Via `library.dedaub.com/decompile`:

```solidity
function __function_selector__() public nonPayable {
    CALLDATACOPY(32, 0, msg.data.length);
    v0 = 0x167.delegatecall(0x618dc65efefefefefefefefefefefefefefefefefefefefe).gas(msg.gas);
    require(v0 != 0, 0, RETURNDATASIZE()); // checks call status, propagates error data on error
    return MEM[0:RETURNDATASIZE()];
}
```

## What it looks like to the calling contract

Consider an example using HTS:

* There's a (Hedera native) HTS token available, say at address `0.0.A`.
* There's some address of interest, say `0xNNNNNNNNNNNNNNNNNNNN`
* Contract thus has `0.0.A` as the address of some token, thinking it is an ERC-20 token, and has
  the address `0xNNNNNNNNNNNNNNNNNNNN`, and it wants to know how much of that token `0.0.A` that
  address owns.
* So the contract wants to call the defined ERC-20 method
  `balanceOf(address) external view returns (uint256)` on that token, so it:
* Does a contract call to _the token_, at address `0.0.A`, using
  selector `0x70a08231`[^†]
  passing the address of interest, `0xNNNNNNNNNNNNNNNNNNNN`, as an argument:
  * `(bool success, uint256 amount) = 0xA.staticcall{gas:5000}(abi.encodeWithSignature("balanceOf(address)", 0xNNNNNNNNNNNNNNNNNNNN)`[^‡]
  * where `0xA` is the account num alias for `0.0.A` (aka "long-zero")
* If `success` is true, then that address's balance is available in `amount`.

[^†]: Via foundry tool: `cast sig balanceOf(address)`
[^‡]: `balanceOf` is a `view` function, so `staticcall` works.  For non-`view` functions
use `call`, of course.

(Or more likely, developer has in his project a Solidity interface that holds all the ERC-20 methods,
and uses that instead of a raw call.)

## How it works

(Example is for HAS system contract, but it works _similarly_ for HTS and HSS.  Not _exactly_
the same _flow_ for technical reasons, but very similarly.)

1. `FrameBuilder.buildInitialFrameWith` - build a `MessageFrame for this transaction, if it is
   a contract _call_ then:
2. `FrameBuilder.finishedAsCall` - finish building the `MessageFrame` for this message call; need to get the
   bytecode to execute
3. `ProxyEvmAccount.getEvmCode` - get the bytecode to be executed for this call to an account;
   if the selector is one of the methods that is proxied _and_ the account id given exists then
   return the customized redirect contract for this account address; otherwise return the empty
   bytecode (which lets the EVM do the "successful noop" call of a nonexistent contract)
   * _n.b._: "_and_ the account id given exists": Only for HAS and HSS at this time, not yet for HTS.
   * _n.b._: The account here - the `ProxyEvmAccount` instance, is not the usual representation of
     a Hedera account, but is a subclass of the Besu representation of an EVM "account".  We must
     use this representation whenever using the proxy contract technique, so that we have an address
     that works with the Besu `Call` operation.
4. `DispatchingEvmFrameState.getAccountRedirectCode` - given account id return the bytecode
   for the redirect bytecode specialized for this account
5. From this point it behaves like any other contract call, and the bytecode is executed
6. In that bytecode it will do a delegate call to the HAS system contract, passing along the
   original calldata, and return the result returned from the system contract.  Thus: It is
   behaves as a proxy/intermediary contract shimming the call to an _account address_ to a call
   to the _system contract address_.
   * _n.b.:_ This is the _sole_ exception to the Smart Contract Security Model v2 rule that
     prohibits a _delegate_ call to a system contract method:  If it is done by the system by using
     its redirect contract.  (See `FrameUtils.callTypeOf()`.)

## Other decompiles/disassemblies of the redirect contract

### 3-address

```
function __function_selector__() public {
    Begin block 0x0
    prev=[], succ=[0xb, 0xf]
    =================================
    0x0: v0(0x80) = CONST
    0x2: v2(0x40) = CONST
    0x4: MSTORE v2(0x40), v0(0x80)
    0x5: v5 = CALLVALUE
    0x7: v7 = ISZERO v5
    0x8: v8(0xf) = CONST
    0xa: JUMPI v8(0xf), v7

    Begin block 0xb
    prev=[0x0], succ=[]
    =================================
    0xb: vb(0x0) = CONST
    0xe: REVERT vb(0x0), vb(0x0)

    Begin block 0xf
    prev=[0x0], succ=[0x58, 0x54]
    =================================
    0x11: v11(0x0) = CONST
    0x13: v13(0x167) = CONST
    0x18: v18(0x618dc65efefefefefefefefefefefefefefefefefefefefe) = CONST
    0x31: v31(0x0) = CONST
    0x33: MSTORE v31(0x0), v18(0x618dc65efefefefefefefefefefefefefefefefefefefefe)
    0x34: v34 = CALLDATASIZE
    0x35: v35(0x0) = CONST
    0x37: v37(0x20) = CONST
    0x39: CALLDATACOPY v37(0x20), v35(0x0), v34
    0x3a: v3a(0x0) = CONST
    0x3d: v3d = CALLDATASIZE
    0x3e: v3e(0x18) = CONST
    0x40: v40 = ADD v3e(0x18), v3d
    0x41: v41(0x8) = CONST
    0x44: v44 = GAS
    0x45: v45 = DELEGATECALL v44, v13(0x167), v41(0x8), v40, v3a(0x0), v3a(0x0)
    0x46: v46 = RETURNDATASIZE
    0x48: v48(0x0) = CONST
    0x4b: RETURNDATACOPY v48(0x0), v48(0x0), v46
    0x4d: v4d(0x0) = CONST
    0x50: v50 = EQ v45, v4d(0x0)
    0x51: v51(0x58) = CONST
    0x53: JUMPI v51(0x58), v50

    Begin block 0x58
    prev=[0xf], succ=[]
    =================================
    0x5a: v5a(0x0) = CONST
    0x5c: REVERT v5a(0x0), v46

    Begin block 0x54
    prev=[0xf], succ=[]
    =================================
    0x55: v55(0x0) = CONST
    0x57: RETURN v55(0x0), v46

}
```

### Disassembly

Via `library.dedaub.com/decompile`:

```
 0x0: PUSH1     0x80
 0x2: PUSH1     0x40
 0x4: MSTORE
 0x5: CALLVALUE
 0x6: DUP1
 0x7: ISZERO
 0x8: PUSH1     0xf
 0xa: JUMPI
 0xb: PUSH1     0x0
 0xd: DUP1
 0xe: REVERT
 0xf: JUMPDEST
0x10: POP
0x11: PUSH1     0x0
0x13: PUSH2     0x167
0x16: SWAP1
0x17: POP
0x18: PUSH24    0x618dc65efefefefefefefefefefefefefefefefefefefefe
0x31: PUSH1     0x0
0x33: MSTORE
0x34: CALLDATASIZE
0x35: PUSH1     0x0
0x37: PUSH1     0x20
0x39: CALLDATACOPY
0x3a: PUSH1     0x0
0x3c: DUP1
0x3d: CALLDATASIZE
0x3e: PUSH1     0x18
0x40: ADD
0x41: PUSH1     0x8
0x43: DUP5
0x44: GAS
0x45: DELEGATECALL
0x46: RETURNDATASIZE
0x47: DUP1
0x48: PUSH1     0x0
0x4a: DUP1
0x4b: RETURNDATACOPY
0x4c: DUP2
0x4d: PUSH1     0x0
0x4f: DUP2
0x50: EQ
0x51: PUSH1     0x58
0x53: JUMPI
0x54: DUP2
0x55: PUSH1     0x0
0x57: RETURN
0x58: JUMPDEST
0x59: DUP2
0x5a: PUSH1     0x0
0x5c: REVERT
0x5d: INVALID
// remaining bytes elided: belongs to Solidity contract metadata
```
