# Cancun Fork Support

## Purpose

The Ethereum "Cancun" hardfork introduces a number of changes to the EVM that will need to be implemented to maintain EVM
Equivalence. There are four different HIPs covering this feature (tracked in  
  epic [#11697](https://github.com/hashgraph/hedera-services/issues/11697):

* [HIP-865](https://hips.hedera.com/hip/hip-865): Add EVM Support for transient storage and memory 
  copy Cancun opcodes (issue [#11699](https://github.com/hashgraph/hedera-services/issues/11699))
* [HIP-866](https://hips.hedera.com/hip/hip-866): Add EVM compatibility for non-supported Cancun blob features
  (issue [#11700](https://github.com/hashgraph/hedera-services/issues/11700))
* [HIP-867](https://hips.hedera.com/hip/hip-867): Add Smart Contract Services Support for KZG Point 
  Evaluation Precompiled Function (issue [#11701](https://github.com/hashgraph/hedera-services/issues/11701))
* [HIP-868](https://hips.hedera.com/hip/hip-868): Support Cancun Self-Destruct  Semantics in Smart
  Contract Services (issue [#11702](https://github.com/hashgraph/hedera-services/issues/11702))

This document describes how the HIPs will be implemented in Hedera Services.

Generally speaking there are four strategies that will be used:

1. Adopt without change. This includes the `MCOPY`, `TSTORE`, and `TLOAD` operations as well as the KZG precompile.
2. Adapt with Hedera Considerations. This includes the `SELFDESTRUCT` changes.
3. Do not adopt, but stub out with placeholders. This includes the `VERSIONEDHASH` and `BLOBBASEFEE` operations.
4. Do not adopt. This includes all Blob handling and the "Type 3" blob transaction.

### Example User Story

<!-- **TODO(Nana): more specific user stories highlighting the capabilities to users** -->

* As a smart contract developer, I want to use current versions of solidity that may generate opcodes that are only
  available in the Cancun fork.
  * These opcodes will enable me to write safer contracts at less gas cost (e.g., transient storage opcodes)
  * These opcodes will make common operations cost less (e.g., `MCOPY` opcode)
* As a smart contract developer, I want supported EVM opcodes to behave as they are defined to do, even when those
  specifications change (e.g., `SELFDESTRUCT`)
* As a Hedera developer, I want to preserve maximum future design space to adopt, or not adopt, blobs.
* As an end user, I want prompt and accurate failures if I attempt to use Blob features in Hedera.
  * And as a smart contract developer I want attempts to _use_ internal blob-support features (e.g.,
    opcodes `VERSIONEDHASH` and `BLOBBASEFEE`) to behave in a predictable manner

## Goals

The principal goal is to maintain EVM and Solidity/Vyper/etc. compatibility, where the bytecode from contracts compiled
with Cancun enabled smart contract compilers still function correctly.

Where inclusion of features don't require additional work, they too should be included.

Where we have existing overrides, opcodes that change those behaviors should still operate in a fashion that is in
harmony with the EVM equivalence requirements and the Hedera requirements to the extent possible. Working with Hedera
takes priority when there is unavoidable conflict.

Opcodes that interact with blobs should not cause the contracts to break, but should not require blobs to work.

## Non Goals

It is not a goal to introduce blobs into Hedera. It is also not a goal to restrict design space nor to dictate future
design directions for support or non-support of blobs.

Cancun support in mono-services.

## Implementation

### New Cancun EVM

A new EVM version will be created that will be based off of the Cancun EVM - which is from a Besu
release `>24.1.2`.  A new enum value, `HederaEvmVersion.VERSION_050` will have the value `v0.50`.  

Setting the `@ConfigProperty` `ContractsConfig.evmVersion` (string value `contracts.evm.version`) 
to `v0.50` (from `v0.46`) will activate the Cancun EVM.

Simply activating the Cancun EVM - with no changes or overrides - will immediately provide the 
following features:

* `MCOPY` operation
* `TLOAD` and `TSTORE` operations
* `VERSIONEDHASH` and `BLOBBASEFEE` operations that query the `TxValues` Object

### Upgrade to latest Besu `GasCalculator`

Update `CustomGasCalculator` to inherit from Besu's `CancunGasCalculator`.

* (This needs to be part of the regular EVM module upgrade - it was last updated for the London
release, wasn't done for Shanghai.)

### KZG precompile initialization

The KZG precompiles needs to be set up properly:

* A native library loaded
* The trusted setup ([big file of community generated constants](https://github.com/ethereum/c-kzg-4844/blob/main/src/trusted_setup.txt))
  loaded from a file and processed
  * See [_Proto-danksharding and the Ceremony_](https://ceremony.ethereum.org/)

This setup (a call to BESU's `KZGPointEvalPrecompiledContract.init()`) is done by BESU outside of
the EVM client library, during BESU's node's initialization.  (And it is only done _once_ per BESU
instance.)

For Hedera, the `ServicesV050` module will call the init routine in the `provideEVM` method.

(The BESU EVM client library artifact (jar) contains, as resources, both the trusted setup file and
the [native library](https://github.com/ethereum/c-kzg-4844/tree/main) that implements the required
"Polynomial Commitments" cryptographic functions.)

### Set Correct Values in TxValues

There are two new fields in the `TxValues` object that need to be set correctly. We need to ensure
that `versionedHashes` is set to an empty list and that `blobGasPrice` is set to one. We should be able to do that in all
EVM versions so setting them unconditionally in the `MessageFrame` construction in `HederaEvmTxProcessor` should be
sufficient.

<!-- **TODO(Nana): Set them in the EVM versions how? Is there a specific class that needs to be updated or a method that needs to be overridden?** -->


### Update Hedera's CustomSelfDestructOperation behavior

The current Hedera override class, `CustomSelfDestructOperation`, will be updated so that it registers, 
with the frame, the executing contract for deletion if either:

* pre-Cancun semantics, or
* post-Cancun semantics and the contract was created in the same frame
  * the latter information is available in the frame itself

A constructor parameter will choose which semantics to implement (which matches the way BESU does it,
    though it isn't _necessary_ to match it).

## Acceptance Tests

Verify the presence of the following features in the new EVM via new smart contracts exercising their features:

* `MCOPY` operation - exists in V050, but not in V046
* `TLOAD` and `TSTORE` operations - exists in V050, but not in V046
* KZG Precompiled contract - exists in V050, but not in V046
  * Also - a test case from BESU, consisting of a single call to the KZG precompile that has the
    correct input to return `SUCCESS`

Verify the presence of the following features in their "no blobs" state:

* `VERSIONEDHASH` will always return zero
* `BLOBBASEFEE` will always return 1

Verify Type 3 transactions produce errors when passed into EthereumTransaction:

* A well-formed "Type 3" blob transaction (without blob but with a versioned hash)
* A poorly formed "Type 3" transaction with valid RLP but invalid in some fashion
* A poorly formed "Type 3" transaction with the first byte `0x03` but the remainder is invalid RLP

Verify that the new behavior of the `SELFDESTRUCT` operation is correct:

* Verify that a self-destruct of a contract created in the same transaction deletes the contract
* Verify that a self-destruct of a contract not created in the same transaction leaves the contract in place
* For both of the above, verify that the hbar balance is correctly sent to their beneficiary
  * But only where allowed by Hedera semantics (e.g., w.r.t. beneficiary account existence and type,
    and required signatures)

