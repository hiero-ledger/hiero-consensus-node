# TSS-Library

## Summary

Provide necessary components for signing messages using a TSS scheme.

| Metadata           | Entities                                          |
|:-------------------|:--------------------------------------------------|
| Designers          | Austin, Cody, Edward, Rohit, Maxi,  Platform team |
| Functional Impacts | Platform team. Release engineering. DevOps        |
| Related Proposals  | TSS-Roster, TSS-Ledger-Id                         |
| Version            | 2                                                 |

## Purpose and Context

This document covers the implementation of all necessary components for building a set of generic libraries that provides the operations to sign and verify information using a Threshold Signature Scheme (TSS) and EC Cryptography.

A threshold signature scheme (TSS) aims to enable a threshold number of participants (shareholders) to securely and efficiently generate succinct aggregate signatures to cryptographically sign and verify signatures in the presence of some corruption threshold in a decentralized network.

In that scheme, a static public key is produced that doesn't change even when the number of participants in the scheme varies. Such property is important for producing proofs that are easily consumable and verifiable by external entities.

Our implementation will be based on [Groth21](https://eprint.iacr.org/2021/339) . This algorithm generates a public key and a private key for each participant. The private key is distributed as key-splits (or shares of shares), and no single party knows the complete aggregated private key.
Only a threshold number of key-splits can produce a valid signature that the aggregated public key can later verify. To achieve that goal, it uses BLS signatures, Shamir's secret sharing, ElGamal, and Zero-Knowledge Proofs.

This proposal assumes no relation with the platform and defines a generic component that any consumer can integrate. It only assumes a channel exists to connect participants where the message sender's identity has been previously validated. The process of sending messages through that channel and receiving the responses is also outside the scope of this proposal.
Additionally, participants will need access to each other's public key. While the generation of the public/private keys is included in this proposal, the distribution aspect, the loading, and the in-memory interpretation from each participant are outside the scope of this proposal. The related proposal, TSS-Ledger-Id, provides an overview of the process and background for TSS and how it impacts the platform’s functionality.

### Glossary

- **BLS (Boneh, Lynn, and Shacham) Signatures**: BLS signatures are succinct and allow aggregation.
- **TSS (Threshold Signature Scheme)**: A cryptographic signing scheme in which a minimum number of parties (reconstruction threshold) must collaborate to produce an aggregate signature that can be used to sign messages and an aggregate public key that can be used to verify that signature.
- **Groth 21**: Publicly verifiable secret sharing and resharing schemes that enable secure and efficient distribution and management of secret shares, with many possible use cases supporting applications in distributed key generation and threshold signatures.  As mentioned, it uses Shamir's secret sharing, ElGamal, and Zero-Knowledge Proofs.
- **Shamir’s Secret Sharing**: In Shamir’s SS, a secret `s` is divided into `n` shares by a dealer, and shares are sent to shareholders secretly. The secret `s` is shared among `n` shareholders so that (a) any party with at least `t` shares can recover the secret, and (b) any party with fewer than `t` shares cannot obtain the secret.
- **ElGamal**: On a message, the ElGamal signature scheme produces a signature consisting of two elements `(r, s)`, where `r` is a random number, and `s` is computed from the message, a signer's secret key, and `r`.
- **SNARK**: A proof system for proving arbitrary statements (circuits/programs).
- **Zero-Knowledge Proofs**: ZKProofs are a proof system where one can prove possession of certain information, e.g., a secret key, without revealing that information or any interaction between the prover and verifier.
- **NIZK**: A non-interactive zero-knowledge proof for a statement. In TSS, we use NIZK proofs to encode the correctness of the secret sharing.
- **Elliptic Curve (EC)**: `Elliptic` is not elliptic in the sense of an `oval circle`. In the field `Fp`, an `EC` is like a non-connected cloud of points where all points satisfy an equation, and all operations are performed modulo `p`. Some elliptic curves are pairing-friendly.
- **Bilinear Pairings**: These are mathematical functions used in cryptography to map two elements of different groups of an elliptic curve to a single value in another group in a way that preserves specific algebraic properties.
- **Fields**: Fields are mathematical structures where addition, subtraction, multiplication, and division are defined and behave as expected (excluding division by zero). Finite fields are especially important for EC-cryptography, where all the operations are performed modulo a big prime number.
- **Groups**: Groups are sets equipped with an operation (like addition or multiplication) that satisfies certain conditions (closure, associativity, identity element, and inverses).
- **Share**: Represents a piece of the necessary public/private elements to create signatures. Each share is in itself a valid Pairings-Key.
- **Polynomial Commitment**: It’s a process that enables evaluations of a polynomial at specific points to be verified without revealing the entire polynomial.
- **Participant**: Is any party involved in the distributed key generation protocol.
- **Participant Directory**: An address book of participants of the distributed key generation protocol that holds information of the participant executing the protocol and all the others.

### Goals

- **Usability**: Design user-friendly libraries with a public API that are easy to integrate.
- **EVM support**: Generated signature and public keys should be compatible with EVM precompiled functions so that signature validation can be done on smart contracts without incurring an excessive gas cost.
- **Security**: Our produced code should be able to pass internal and external security audits.
- **Flexibility**: Minimize the impact of introducing support for other elliptic curves.
- **Independent Release**: When applicable, the new libraries should separate the release cycle from the platform.

### Non-Goals

- Implement support for elliptic curve arithmetic in Java.
- Support any system/architecture other than Windows amd64, Linux amd64 and arm64, and MacOS amd64 and arm64.
- Creation of the building artifacts and plugins for rust code.
- This proposal covers the implementation of a tool similar to ssh-keygen to generate those keys. Still, the generation, persistence, distribution, and loading of those keys are outside the scope of this proposal.

## Changes

### Core Behaviors

The proposed TSS solution is based on [Groth21](https://eprint.iacr.org/2021/339).

Groth21 is a non-interactive, publicly verifiable secret-sharing scheme where a dealer can construct a Shamir secret sharing of a field element and confidentially yet verifiably distribute shares to multiple receivers. It includes a distributed resharing protocol that preserves the public key but creates a fresh secret sharing of the secret key and hands it to a set of receivers, which may or may not overlap with the original set of shareholders.

### Overview

Participants can hold one or more shares, each of which can be used to sign a message. The goal is to generate an aggregate signature valid if a threshold number of individual signatures are combined.

Each participant brings their own BLS key private and public pair _(we will call them TssEncryptionKeys as every key created is a valid bls key but serving different purposes)_.
They share their public TssEncryptionKeys with all other participants while securing their private keys.
Before the protocol begins, all participants agree on the cryptographic parameters (type of curve and what pairing group will be used for public keys and signatures).
When the protocol is initialized, a participant directory is built. This directory includes the number of participants, all participant’s public TssEncryptionKeys, and the shares they own.

Each participant generates portions of a secret share and distributes them among the other participants using the following process:

1. A random share is created and mathematically split (Using Shamir's Secret Sharing and interpolation polynomials) into a known number of key-splits (or shares of shares).
2. Each portion is encrypted with the share owner's tss encryption public key, ensuring only the intended recipient can read it.
3. A message includes all encrypted values so only the intended recipients can decrypt their respective portions of the secret share.

(e.g., for a directory of 10 participants and 10 shares distributing 1 share each with a threshold value of 6, `participant1` will generate a message out of a random key that will contain 10 portions of that key, each one encrypted under each participant' public key) This setup allows participants to share secret information securely. The message also contains additional information necessary for validation (Such as a polynomial commitment and a NIZK proof).

Upon receiving a threshold number of messages, each participant:

1. Validates the message and decrypts the information encrypted with their tss encryption private key.
2. Aggregates the decrypted information to generate a private key for each owned share.
3. Retrieves a public key for each share in the system to validate signatures.

(e.g., for a directory of 5 participants and 10 shares with a threshold value of 6 where `participant1` has 2 shares, `participant1` will collect at least 6 valid messages from all participants, take the first and second portions of each message, decrypt with its tss encryption private key and aggregate them, so they become the first and second owned secret share)

Individual signing can then begin. Participants use their private shares to sign messages.

An aggregate signature is created when at least a threshold number of signatures are combined. This aggregate signature can be validated using the combined value of the public shares in the directory.

The process restarts whenever the number of participants or the shares assigned to each change. However, the initially generated group public key remains unchanged to maintain consistency. New secret information for shares is created using existing data, ensuring the aggregate signature can still be verified with the original group public key.

### Architecture

Although the library is designed for agnostic ally from the client, this section describes its place in hedera ecosystem and how it will interact and be used by the system:

![img_5.svg](img_5.svg)

1. **TSS Lib**: Used to create shares, create TSS messages to send to other nodes, assemble shared public keys, and sign messages.
2. **Pairings Signatures Library**: This library provides cryptographic objects (PrivateKey, PublicKey, and Signature) and operations to sign and verify signatures.
3. **Pairings API**: An API definition for the arithmetic operations required to work with specific EC curves and the underlying Groups, Fields, and Pairings concepts. This API minimizes the impact of changing to different curves.
4. **Bilinear Pairings Impl**: Implementing the Bilinear Pairings API that will be loaded at runtime using Java’s service provider (SPI) mechanism. Multiple implementations can be provided to support different curves, and the Java service provider approach facilitates easily changing between implementations and dependencies.
5. **Native Support Library**: Provides a set of generic functions for loading native libraries in different system architectures when packaged in a jar, using a predefined organization so they can be accessed with JNI.
6. **EC-KeyGen:** is a utility module that enables the node operator to generate a bootstrap public/private key pair.

### Module organization and repositories

![img_6.svg](img_6.svg)

1. **hedera-cryptography**: This is a separate repository for hosting cryptography-related libraries.  It is necessary to facilitate our build process, which includes Rust libraries. It also provides independent release cycles.
2. **hedera-common-nativesupport**: This gradle module enables loading compiled native libraries to be used with JNI.
3. **plataform.core**: Represents the part of the consensus node that will be interacting directly with the tss library.
4. **hedera-cryptography-tss**: Gradle module for the TSS Library.
5. **hedera-cryptography-signatures**: Gradle module for the Bilinear Pairings Signature Library.
6. **hedera-cryptography-pairings-api**: Gradle module for the Bilinear Pairings API. Minimizes the impact of adding or removing implementations.
7. **hedera-cryptography-altbn128**: Gradle module that will implement the Bilinear Pairings API using alt-bn128 elliptic curve using arkworks library implemented in rust.

### Using arkworks

Given that it is outside the scope of this project the creation and support of elliptic curve arithmetic and the mathematical operations that allow resolving a pairings operation and that there is no library implemented in Java with production-grade security that can help us with this goal, we decided to include the wrapping of arkworks bn254 module and delegate the operations to that library. We will wrap the invocations with a Java abstraction layer that will facilitate switching curves and other parameters when and if necessary.

### Handling of multilanguage modules and native code distribution

The software provided by this approach will require multi-language modules (rust \+ Java). Rust code must be compiled into binary libraries and accessed through JNI. Given the immature state of development, Project Panama is not considered for this proposal, but it might be for future development.

There are two possible ways of loading libraries and accessing them through JNI:

1) Libraries are installed on the system as shared object(SO) libraries and found via the classpath and system library path.
2) Distributed with the application jars, unpacked, and loaded at runtime.

We want to ensure that dependent software does not require the installation of any additional dependencies other than those distributed in the jars, so we are choosing option 2\. We will provide a library `hedera-common-nativesupport` to help load and use binary libraries and binding with JNI. The low-level details of the build logic for this to work are outside the scope of this proposal. A high overview is mentioned for the benefit of readers.

In multilanguage projects, Rust code will be placed in a structure such as:

```
hedera-multilanguage-project
    ├── main
    │   ├── java
    │   │   └── **
    │   └── rust
    │       └── **
    ├── test
    │   └── java
    │       └── **
    ├── cargo.toml
    └── build.gradle.kts
```

Then, it will be cross-compiled into a set of binary libraries and packaged in the same jar as the Java code for accessing it.

Rust code will be compiled first, and the build process will create the following folder structure, where binary files will be placed and then distributed. They will be arranged by platform identifier, as returned by `System.getProperty("os.name")` and `System.getProperty("os.arch")`.

```
/software
    ├── darwin
    │   ├── amd64
    │   │   └── native_lib.dylib
    │   └── arm64
    │       └── native_lib.dylib
    ├── linux
    │   ├── amd64
    │   │   └── native_lib.so
    │   └── arm64
    │       └── native_lib.so
    └── windows
        └── amd64
            └── native_lib.dll
```

This whole process will be produced both locally and in CI/CD.

### Libraries Specifications

#### Hedera Cryptography Pairings API

##### Overview

This API will expose general arithmetic operations to work with elliptic curves and bilinear pairings that implementations must provide.

##### Public API

![img_7.svg](img_7.svg)

###### *`Curve`*

An interface that represents the different types of elliptic curves.

###### *`PairingFriendlyCurve`*

This class provides access to each group (G₁, G₂) enabled for pairings operations and the scalar field associated with the curve. It provides an initialization method to load all necessary dependencies for the library to work. Callers are requested to invoke that method before start using the library. Implementations of this API should decide if they provide one or many curves.

###### *`Field`*

Represents a finite field used in a pairing-based cryptography scheme. A finite field is a ring of scalar values \[0,1,...r-1, 0, 1, ...\] where all operations are done modulus a prime number P. All operations inside the field produce a value that belongs to it.

Each scalar element in the field is a `FieldElement`. Supports the following operations:

- create an element out of its serialized form
- create the zero-element
- create the one-element
- create a random element

###### *`FieldElement`*

A scalar value that is a member of `Field`. In an elliptic curve, these values can be used to operate in certain `Group` operations. Supports the following operations:

- Addition
- Subtraction
- Power
- Multiplication
- Serialization/Deserialization
- Inversion

###### *`Group`*

Represents a mathematical group used in a pairing-based cryptography system. A group in this context is a set of elements (curve points) with operations that satisfy the group properties:

* closure
* associativity
* identity
* invertibility

Curves can be defined by more than one group. This class provides methods to obtain elements of the group represented by the instance and some operations that handle multiple points.

- Create a point out of its serialized form
- Create a random point
- (hashToGroup) create a point out of a hashed (sha256?) value of a byte array
- Create the zero point or point at infinity
- Retrieve the group generator point
- Add a list of points and return the aggregate result
- Multiply the generator point with a list of scalars and return each result

###### *`GroupElement`*

Represents an element of `Group`. In an elliptic curve, these are points.
Supports the following operations:

- addition
- multiplication for a scalar
- serialization

###### *`BillinearPairing`*

Represents a bilinear pairing operation used in cryptographic protocols. A pairing is a map: e : G₁ × G₂ \-\> Gₜ which can satisfy these properties:

* Bilinearity: `a`, `b` member of `Fq` (Finite Field a.k.a. `Field`), `P` member of `G₁`, and `Q` member of `G₂`, then `e(a×P, b×Q) = e(ab×P, Q) = e(P, ab×Q) = e(P, Q)^(ab)`
* Non-degeneracy: `e != 1`
* Computability: There should be an efficient way to compute `e`.

Supports the following operations:

- Compare: compares two pairings and returns if they are equal.

#### Hedera Cryptography alt-bn128

##### Overview

Implementation module of the parings API for `alt-bn128` (`bn254`) pairings-friendly curve. That curve has been chosen due to the existence of EVM precompiles that allow this verification in a cheap way for smart contract users. The underlying elliptic curve and fields algebra operations and primitives will be implemented using `arkwrorks` library, accessed through a JNI interface. The module will include Java and Rust code.

##### Implementation details

`alt-bn128` curve uses a scalar field based on the prime number:

* r=21888242871839275222246405745257275088548364400416034343698204186575808495617
* p=21888242871839275222246405745257275088548364400416034343698204186575808495617
* q=21888242871839275222246405745257275088696311157297823662689037894645226208583
* Generator 5\.
* G1 generator: (1, 2\)
* G2 generator: (10857046999023057135944570762232829481370756359578518086990519993285655852781,  11559732032986387107991004021392285783925812861821192530917403151452391805634, 8495653923123431417604973247489272438418190587263600148770280649306958101930, 4082367875863433681332203403145435568316851327593401208105741076214120093531\)

It is defined by groups `Group1` and `Group2,` identified by an enum value.
This library will not have a public API as it is intended to be used only as a runtime dependency.

###### *Encoding:*

`FieldElements` scalars are encoded as 32-byte little-endian numbers. Curve points are encoded as two components (x, y) that differ in size for each curve group. Group 1 `GroupElements` have the form (X, Y) for each 32 little-endian bytes number. Group 2 `GroupElements` have the form (X₁, X₂, Y₁, Y₂) each 32 little-endian bytes numbers.

The point at infinity in all cases is stored at 32-byte 0 value for each component.

##### Hash to curve Note

hashing algorithm used by `pairings-api` does need to be the same as one of the hashing algorithms available to smart contracts,
and likely one that is available as a precompile on the EVM.

###### *Code Organization Structure:*

```
hedera-cryptography-altbn128
    ├── main
    │   ├── java
    │   │   └── **
    │   └── rust
    │       └── **
    └── test
        └── java
            └── **
```

#### Hedera Cryptography Pairings Signatures Library

##### Overview

This provides public keys, private keys, signatures, and operations to produce each other.

##### Public API

![img.png](img.png)

###### *`SignatureSchema`*

A pairings signature scheme can be implemented with different curves and group assignment configurations. For example, two different configurations might consist of the `ALT_BN_128` curve using short signatures or short public keys (which translates to choosing `Group1` to generate public key elements or `Group2` for the same purpose). This class allows those parameters to be specified internally, so library users don't need to keep track of the specific parameters used to produce the keys or signatures.

###### *`PairingsPrivateKey`*

A private key is generated using the pairings API. It is a Random `FieldElement` out of a 32-byte seed.
This class provides the following operations:

- Create a public key: The scalar multiplication of the private key and the `generator` `GroupElement` of the `Group` configured in the `SignatureSchema` for public keys.
- Sign a message: It is the scalar multiplication of the private key element and the `hashToGroup` `GroupElement` from the message of the `Group` configured in the `SignatureSchema` for signatures.

###### *`PairingsPublicKey`*

A public key is generated using the pairings API. Under the hood is a point in the selected curve (`GroupElement`).
This class provides the following operations:

- Verify a signed message against a signature relies on the verification executed by the signature class.

###### *`PairingsSignature`*

A signature is generated with the private key that can be verified with the public key. Under the hood, it is also a point in the curve (`GroupElement)`.
This class provides the following operations:

- Verify against a public key and the original signed message: This operation uses the pairings operation and verifies that:

```
pairing.pairingBetween(signatureElement, publicKeyGroup.getGenerator());
==
pairing.pairingBetween(messageHashedToGroup, publicKey.keyElement());
```

###### *`ElGamalUtil`*

A utility class that helps to encrypt a decrypt using the ElGamal method. Operations:

- Decrypt a message:
- ElGamal encrypts:

```
var r =  Field.randomFieldElement(random);
var c1 = this.groupElement().group().generator().mul(&r);
var c2 = this.groupElement().mul(&r).add(this.groupElement().group().generator().generator.mul(&m));
return { c1, c2 }
```

##### Serialization Note

It will be the general case that consumers of this library don’t need to understand the elements as points on the curve to operate algebraically with them, so it is sufficient for them to interact with an opaque representation. There are two strategies for getting that representation:

1. **Use arkworks-based serialization mechanism** This serialization mechanism uses the encoding defined for the `parings-api` and its ability to produce byte arrays from its components.

   **Pros:**
   - Serialization is guaranteed to be canonical.
   - Given that internal consumers don't need to operate with values and an opaque representation is enough, the approach decouples the serialization mechanism
   from the pairing internal structure and allows to switch curves with 0 impact on the consumers.
   - Simple and reduced effort, low infra support needed.

   **Cons:**
   - Coupled to the arkworks serialization mechanism
   - There is nothing formal to share the specification of the format. It should be through technical documentation as there is no technical interface we can share.

2. **Use protobuf serialization.** This serialization mechanism consists of generating a structure that represents the internal representation of PublicKey, PrivateKey, and Signature in a defined protobuf schema.
   We would need to extract the information from the pairings API implementation and produce a protobuf object to expose.

   **Pros:**
   - Allows to share a schema to know how to interpret the curve
   - Any client that can use protobuf can interpret our elements

   **Cons:**
   - Consumers needs to be able to operate with protobuf schemas.
   - By producing a structure for the internals of the pairings API, switching curves impacts our code.

It was decided that keys and signatures would be represented as opaque byte arrays in little-endian form when recorded in the state and published to the block stream.
We will publish a byte layout of the data format for external consumers.
The code that sets up smart contracts for execution will be responsible for parsing the byte array and translating the little-endian data into the big-endian form that is used in the EVM.

**_Serialized form of a `PairingsPrivateKey`_**

![img_8.svg](img_8.svg)
*_This example corresponds to AltBN128, other curves might have different lengths._

**_Serialized form of a `PairingsPublicKey` or `PairingsSignature`_**

![img_8.svg](img_9.svg)
*_This example corresponds to AltBN128, other curves might have different lengths._

Curve ID value `1` is `ALT_BN_128`, other curves implementations might choose a different id for their curves but it is up to the implementation to select the id.
The idea of using the id is to be able to determine if a particular key or signature can be interpreted and it is compatible with the plugged implementation of `pairings-api`

#### Hedera Cryptography TSS Library

##### Overview

This library implements the Groth21 TSS-specific operations.

##### Public Interface

###### *`TssMessage`*

A data structure for distributing encrypted shares of a secret among all participants in a way that only the intended participant can see part of the share. It includes auxiliary information used to validate its correctness and assemble an aggregate public key, i.e., a commitment to a secret share polynomial and a NIZK proof.
Besides their construction, another operation is to retrieve a deterministic byte representation of an instance and retrieve the corresponding instance back from a byte array representation.

###### *`TssShareId`*

A deterministic unique, contiguous starting from 1 identifier for each existent share. a) are unique per share, b) non-0, and c) can be used as input for the polynomial (They are from the same field of the selected curve)

###### *`TssPrivateShare`*

Represents a share owned by the executor of the scheme. Contains a secret value used for signing. It is also an PairingsPrivateKey.

###### *`TssPublicShare`*

Represents a share in the system. It contains public information that can be used to validate each signature. It is also an PairingsPublicKey.

###### *`TssShareSignature`*

Represents a signature created from a TSSPrivateShare.

###### *`TssParticipantDirectory`*

This class contains all the information about the participants in the scheme. This includes participants' `tssEncryptionPublicKeys`, total shares, and owned shares.

###### *`TssService`*

A class that implements all the TSS operations allowing:

* Generate TSSMessages out of a random PrivateShare
* Generate TSSMessages out of a list of PrivateShares
* Verify TSSMessages out of a ParticipantDirectory
* Obtain PrivateShares out of TssMessages for each owned share
* Obtain PublicShares out of TssMessages for each share
* Aggregate PublicShares
* Sign Messages
* Verify Signatures
* Aggregate Signatures

##### Usage

###### *Input*

* Participant's persistent `tssDecryptionPrivateKey` (Private to each participant)
* Number of participants (Public)
* Number of shares per participant (Public)
* A threshold value externally provided:`e.g: t = 5`
* All participants' `tssEncryptionPublicKey`
* A predefined `SignatureSchema` (Public / Constant for all the network)

###### *0\. Bootstrap*

Given a participants directory, e.g:

```
P   #   tssEncryptionPublicKey
-----------------------------
P₁  5   P₁tssEncryptionPublicKey
P₂  2   P₂tssEncryptionPublicKey
P₃  1   P₃tssEncryptionPublicKey
P₄  2   P₄tssEncryptionPublicKey

```

```java
//Given:
TssService service = new TssService(signatureScheme, new Random());
PairingPrivateKey tssEncryptionPrivateKey = loadCurrentParticipantTssEncryptionPrivateKey();
List<PairingPublicKey> tssEncryptionPublicKeys = loadAllParticipantsTssEncryptionPublicKeys();

//Then:
TssParticipantDirectory participantDirectory =
        TssParticipantDirectory.createBuilder()
        .self(/*Identification, different for each participant*/ 0, tssEncryptionPrivateKey)
        .withParticipant(/*Identification:*/0, /*Number of Shares*/5, tssEncryptionPublicKeys.get(0))
        .withParticipant(/*Identification:*/1, /*Number of Shares*/2, tssEncryptionPublicKeys.get(1))
        .withParticipant(/*Identification:*/2, /*Number of Shares*/1, tssEncryptionPublicKeys.get(2))
        .withParticipant(/*Identification:*/3, /*Number of Shares*/1, tssEncryptionPublicKeys.get(3))
        .withThreshold(5)
       .build(signatureScheme);

//After:
//One can then query the directory
int n = participantDirectory.getTotalNumberOfShares();
List<TssShareId> privateShares = participantDirectory.getOwnedSharesIds();
List<TssShareId> shareIds = participantDirectory.getShareIds();
```

Under the hood, a `shareId`: `sid` is generated for each share. And an ownership map is maintained inside the directory: `ShareId`\-\>`Participant`, e.g:

```
sid₁	sid₂	sid₃	sid₄	sid₅	sid₆	sid₇	sid₈	sid₉	sid₁₀
P₁  	P₁  	P₁  	P₁  	P₁  	P₂  	P₂  	P₃  	P₄  	P₄
```

###### *1\. Create TssMessage*

```java
//Creates a TssMessage out of a randomly generated share
TssMessage message = service.generateTssMessage(participantDirectory);
```

**Implementation details** Internally, the process of creating a TssMessage consists of
![img.svg](img.svg)

a. Generation of the shares: In this operation for the bootstrap process, a random  `TssPrivateShare` `k` is created. It is the same process as creating a random PairingsPrivateKey.

b. After that, the key is split into `n` (n=total number of shares) values `Xₛ` by evaluating a polynomial Xₖ at each `ShareId`: `sidᵢ` in the ownership map. The polynomial `Xₖ` is a polynomial with degree `t-1` (t=threshold) with the form: `Xₖ = k + a₁x + … + aₜ₋₁xᵗ⁻¹`\
[ having: `a₁,…,aₜ₋₁`: random coefficients from `SignatureScheme.publicKeyGroup` and `k`'s EC field element.
x is a field element, thus allowing the polynomial to be evaluated for each share id\] Each `sᵢ = Xₖ(sidᵢ)` constitutes a point on the polynomial.

c. Once the `sᵢ` value has been calculated for each `ShareId`: `sidᵢ`, the value: `Cᵢ` will be produced by encrypting the `sᵢ` using the `sidᵢ` owner's `tssEncryptionPublicKey`.
The TssMessage will contain all the encrypted values for all shares.

d. Generation of the Polynomial Commitment: We include a Feldman commitment to the polynomial to detect forms of bad dealing.
For each coefficient in the polynomial `Xₖ` `a₍ₒ₎` to `a₍ₜ₋₁₎`, compute a commitment value by calculating: `gᵢ * aᵢ`  (g multiplied by polynomial coefficient `a₍ᵢ₎` )

e. Generation of the NIZKs proofs: Generate a NIZKs proof that these commitments and the encrypted shares correspond to a valid secret sharing according to the polynomial.

###### *Acting as dealers of `TssMessage`s (outside the scope of the library)*

Using an established channel, each participant will broadcast a single message to be received by all participants while waiting to receive other participants' messages. This functionality is critical for the protocol to work but needs to be handled outside the library. Each participant will validate the received message against the commitment and the NIZK proof.
Invalid messages need to be discarded.

###### *Note about serialization / deserialization of `TssMessage`s*

`TssMessage`s need to be serialized to bytes in a deterministic manner. We will include a protobuf serialization mechanism hidden in the library that will allow instances of `TssMessage` to create a protobuf representation of themselves and serialize to byte arrays, additionally it will allow to construct instances from the byte array representation back to a valid instance.

###### *2\. Validation of TssMessage*

```java
static {
    //given
    List<TssMessage> messages = List.of(someTssMessage, someOtherTssMessage);
    //then
    for(TssMessage m : messages){
        if(!service.verifyTssMessage(participantDirectory, m))
            throw new SomeException("There are non valid tssMessages");
    }
}
```

The validation is produced over the content of the message and does not include the sender's identity, which is assumed to be provided by the external channel. Each message can be validated against the commitment and the proof by:

* Checking that the encrypted shares correspond to the commitments.
* Commitments are consistent with the public values and the generated proof.

###### *3\. Generating Participant's Private Shares & Ledger ID*

```java
// Given some previously agreed upon same set of valid messages for all participants
Set<TssMessage> agreedValidMessages = Set.of(someTssMessage, someOtherTssMessage, etc);
//Get Private Shares
List<TssPrivateShare> privateShares = service.decryptPrivateShares(participantDirectory, agreedValidMessages);
//Get Public Shares
List<TssPublicShare> publicShares = service.computePublicShare(participantDirectory, agreedValidMessages);
```

Given the Participant's `TssEncryptionPrivateKey` and precisely `t` number of validated messages (t=threshold), The participant will decrypt all `Cᵢ` to generate an aggregated value `sᵢ` that will become a  `SecretShare(sidᵢ, sᵢ)` for each `ShareId`: `sidᵢ` owned.

**Note:** All participants must choose the same threshold number of valid `TssMessages`.

![img_1.svg](img_1.svg)

Also, we will extract a `PublicShare` for each `ShareId`: `sidᵢ` in the directory from the list of valid messages. The PublicShare for share `s` is computed by evaluating each polynomial commitment in the common set of messages at `sidᵢ` and then aggregating the results.

###### *4\. Sign and aggregate/validate signatures*

At this point, the participant executing the scheme can start signing, sharing signatures, and validating individual signatures produced by other parties in the scheme. Using each `privateShares` owned by the participant, a message can be signed, producing a `TssShareSignature.`
a. Perform signing:

```java
//Given
byte[] message = getMessageToSign();
//Then
List<TssShareSignature> signatures = service.sign(privateShares, message);
```

Again, it is outside the scope of this library to distribute or collect other participants' signatures.

Multiple `TssShareSignature` can be aggregated to create an aggregate `PairingSignature`. An aggregate `PairingSignature` can be validated against the LedgerId (public key)  if `t` (t=threshold) valid signatures are aggregated. If the threshold is met and the signature is valid, the library will respond with true; if not, it will respond with false.

```java
static {
    //Given
    List<TssShareSignature> signatures = receiveParticipantsSignatures();

    //Then: validation of individual signatures
    List<TssShareSignature> validSignatures = new ArrayList<>(signatures.size());
    for (TssShareSignature signature : signatures) {
        if (service.verifySignature(participantDirectory, publicShares, signature)) {
            validSignatures.add(signature);
        }
    }

    //Producing an aggregate signature
    PairingSignature aggregateSignature = service.aggregateSignatures(validSignatures);
}
```

###### *5\. Rekey Stage*

The rekey process should be executed each time some of the scheme’s parameters change, such as the number of participants or the number of shares assigned to each participant. This rekeying process may also be conducted for reasons other than parameter changes, such as rotating shares.

```java
//Given
List<TssPrivateShare> privateShares = List.of(firstOwnedPrivateShare, secondOwnedPrivateShare, etc);
PairingPrivateKey tssEncryptionPrivateKey = loadCurrentParticipantTssEncryptionPrivateKey();
List<PairingPublicKey> tssEncryptionPublicKeys = loadAllParticipantsTssEncryptionPublicKeys();
TssParticipantDirectory oldParticipantDirectory = getPreviousParticipantDirectory();
int newThreshold = someNewThreshold;

//And:
TssParticipantDirectory newParticipantDirectory =
        TssParticipantDirectory.createBuilder()
                .self(/*Identification, different for each participant*/ 0, tssEncryptionPrivateKey)
                .withParticipant(/*Identification:*/0, /*Number of Shares*/3, tssEncryptionPublicKeys.get(0))
                .withParticipant(/*Identification:*/1, /*Number of Shares*/2, tssEncryptionPublicKeys.get(1))
                .withParticipant(/*Identification:*/2, /*Number of Shares*/1, tssEncryptionPublicKeys.get(2))
                .withParticipant(/*Identification:*/3, /*Number of Shares*/1, tssEncryptionPublicKeys.get(3))
                .withParticipant(/*Identification:*/4, /*Number of Shares*/3, tssEncryptionPublicKeys.get(3))
                .withParticipant(/*Identification:*/5, /*Number of Shares*/2, tssEncryptionPublicKeys.get(3))
                .withThreshold(newThreshold)
                .withPreviousThreshold(oldParticipantDirectory.threshold())
                .build(newThreshold);

//Creates a TssMessage out of a randomly generated share
TssMessage message = service.generateTssMessages(participantDirectory, privateShares);
```

The rekeying process is similar to bootstrap. However, given that the goal is to produce a new set of keys that can still produce signatures that once aggregated, can be validated with the already established PublicKey, it starts with the list of `privateShares` owned. The main difference with the genesis stage is that every participant generates a `TssMessage` from each previously owned `PrivateShare`.

![img_4.svg](img_4.svg)

Once finished, the list of `public/private shares` will be updated, but the previously generated aggregate public key will remain the same.

##### Security Considerations

If an adversary participant knows fewer than a threshold number of decryption keys, they cannot recover enough information to start forging threshold signatures. Adversarial participants may learn the shares of the participants whose keys they have compromised, but more is needed to recover the secret. Adversarial parties might choose low or zero entropy values for protocol inputs such as shares; however, assuring the right threshold value and share numbers per participant will still guarantee sufficient entropy from honest nodes to maintain security in the overall protocol.

#### Hedera Common Native Support

##### Overview

This library provides classes that assist other modules to load native libraries packaged in dependency jars.

##### Constraints

This module will not depend on hedera-services artifacts, so it cannot include logging, metrics, configuration, or any other helper module from that repo.

##### Public API

###### *`OperatingSystem`*

An enum class listing all supported OS.

###### *`Architecture`*

An enum class listing all supported architectures.

###### *`NativeLibrary`*

Helper class that will load a library for the current O.S/Architecture

###### *`SingletonLoader`*

Helper class that will load a library for the current O.S/Architecture

## Test Plan

Refer to: [TSS-Library-Test-Plan.md](./TSS-Library-Test-Plan.md)

### Performance Testing

JMH benchmarks should be provided for signature generation and aggregation. TssMessage validation, and public key aggregation.

We should know the variance (min, max, average, median) time it takes to do the following:

Genesis for 4, 10, 20, 40, and 100 participants. Rekeying for 4, 10, 20, 40, and 100 participants. Shares Amounts distributed randomly: 200, 500, 1000, 2000, 5000 we need to test each configuration 100 times to get confident mins, maxes, and a good average and median

## Security Audit

After this proposal is accepted, we will invite the security team to define the necessary steps for auditing the code.

## Alternatives Considered

- As mentioned before, Java Foreign Function Interface is still immature, so it was not considered for this proposal.
- JRA was discarded, given the possibility of eventually moving to JFF.
- Using JSA through Bouncy Castle implementation was analyzed. BC provides support for EC-Curves, but it does not support Pairings which are required for BLS signatures. [beacon-chain](https://github.com/harmony-dev/beacon-chain-java/tree/master) implemented the support using bouncy castle \+ [Milagro](https://incubator.apache.org/projects/milagro.html), but Milagro project is reported to have little coverage and is not audited. Milagro podling has been retired on 2024-03-21.

## Delivery Plan by Stages

**Stage 1**

Preconditions:

1. hedera-cryptography repository.
2. Gradle multilanguage module with rust compilation plugin.
3. CI/CD pipelines for building artifacts in hedera-cryptography.

* Define the Test plan.
* Define a security plan.
* Implementation of native-support library.
* Implementation of Pairings API using JNI, arkworks, and alt-bn128.
* Implementation of Pairings Signatures library.
* Implementation of EC-key utility.
* Implementation of TSS library public interface (TBD: include mock implementation).
* Execute Test Plan and validation.

**Stage 2**

Preconditions:  hedera-cryptography CI/CD publishes artifacts and they can be referenced from hedera-services.

Implementation of the public interface for the TSS library.

* Implementation of TSS library.
* Performance tests and optimizations.
* Execute Test Plan and validation.
* Execute Security Audits.

## External References

- [https://eprint.iacr.org/2021/339](https://eprint.iacr.org/2021/339)
- [https://crypto.stanford.edu/pbc/notes/elliptic](https://crypto.stanford.edu/pbc/notes/elliptic)
- [https://andrea.corbellini.name/2015/05/17/elliptic-curve-cryptography-a-gentle-introduction/](https://andrea.corbellini.name/2015/05/17/elliptic-curve-cryptography-a-gentle-introduction/)
- [https://www.iacr.org/archive/asiacrypt2001/22480516.pdf](https://www.iacr.org/archive/asiacrypt2001/22480516.pdf)
- [https://hackmd.io/@benjaminion/bls12-381\#Motivation](https://hackmd.io/@benjaminion/bls12-381#Motivation)
- [https://www.johannes-bauer.com/compsci/ecc/](https://www.johannes-bauer.com/compsci/ecc/)
