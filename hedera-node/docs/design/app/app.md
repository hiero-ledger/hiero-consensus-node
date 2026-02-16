# The Application Module

The main module of a Hedera consensus node is the `hedera-app` module. This module depends on all other modules,
and no other modules depend on it. This module contains the main entrypoint of the application which is the `Hedera`
class. This module is responsible for application lifecycle, interfacing with the hashgraph platform, managing the
gRPC or other server processes, constructing and managing the lifecycle of service plugins, generating record streams,
and all other core application tasks. Each of these is broken into a separate package within the module.

The follow packages contain implementation details:
- [`fee`](fees.md): An implementation of `FeeAccumulator` and other fee engine components
- [`grpc`](grpc.md): Components for handling gRPC
- [`record`](records.md): The `RecordStreamManager` is here, along with an implementation of the `RecordBuilder` interfaces
- [`state`](states.md): Classes that implement the SPI interfaces for states, and all things related to merkle trees
- [`throttle`](throttles.md): The implementation of the `ThrottleAccumulator` and the throttle engine
- [`high_volume_pricing`](high-volume-pricing.md): HIP-1313 high-volume pricing model and data-entry rules
- [`workflows`](workflows.md): Classes for the various workflows such as `TransactionWorkflow`, `QueryWorkflow`, `IngestWorkflow`, etc.
- [`special_files`](system_files.md): Reference of special files such as the address book, properties, and exchange rates
