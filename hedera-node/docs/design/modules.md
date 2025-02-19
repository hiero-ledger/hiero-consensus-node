# Internal structure of a module

**INFORMATION:** This document is a draft based on our current ideas. The document might change over time. Next to this
the described architecture might not be reflected directly in the code. Once the service architecture has been finalized
the description inn this documentation must fit to the code.

Since our project is using Gradle as build tool all our modules are Gradle modules. Next to this mostly all modules are
Java modules.

## Gradle module description

Please also refer to the
[documentation of the Hiero Gradle Conventions](https://github.com/hiero-ledger/hiero-gradle-conventions#modules)
which describes a common approach for structuring projects and modules in Hiero and Hedera repositories.

Each module needs a `build.gradle.kts` file that describes the module.
In the `build.gradle.kts` file, you define the type of the module by using one of the
[_Module_ convention plugins](https://github.com/hiero-ledger/hiero-gradle-conventions#plugins).
provided by the Hiero Gradle Conventions.

```
plugins { id("org.hiero.gradle.module.library") }

description = "A minimal module without any dependecies"
```

Note: the `group` and `version` of the module should not be added here. The `group` is the same for all modules that
belong to a _product_, and it is
[defined in `settings.gradle.kts`](https://github.com/hiero-ledger/hiero-gradle-conventions#modules).
For the `version` we use a global definition, too. The current `version` is defined in the `version.txt` file in
the root folder of the project. The `name` of a module is simple created based on the folder name of the module.

## Project sources set

For a Java module all sources must be placed under `src/main/java` and `src/main/resources` while `src/main/java` must
contain all the Java files that should be compiled. All other files must be placed under `src/main/resources`. Since all
our Java based modules are full JPMS modules a `module-info.java` file must be placed directly under `src/main/java`.

Each JPMS module has a unique name that start with `com.hedera.node.app.` and should reflect the usecase of the module.
If you have a module for the service foo the JPMS name might be `com.hedera.node.app.services.foo`. The name of the
module always defines the root package of the module. In the given sample the root package will
be `com.hedera.node.app.services.foo`. Based on that definition no Java class should be placed outside from that
package (outside from `src/main/java/com/hedera/node/app/services/foo`). Resources can be placed directly
under `src/main/resources` if it makes sense. In general it is best practice to place resources that do not need a
specific path below the `src/main/resources/com/hedera/node/app/services/foo` folder, too.

## Tests

All modules can have different types of tests. The `org.hiero.gradle.module.library` plugin provides direct support
for unit tests. Other test sets can be added by applying additional feature plugins:

- `id("org.hiero.gradle.feature.test-hammer")`
- `id("org.hiero.gradle.feature.test-integration")`
- `id("org.hiero.gradle.feature.test-time-consuming")`
- `id("org.hiero.gradle.feature.test-timing-sensitive")`

Next to this, the test fixtures functionality of Gradle is supported.

### Test fixtures

To create clean and readable unit tests it is best practice to provide common functionality for tests in the test
fixtures of a module. To add test fixture support to a module, apply the `id("org.hiero.gradle.feature.test-fixtures")`
plugin.

All Java sources for the test fixtures must be placed under `src/testFixtures/java`. Additional resources that should be
shared for tests can be placed under `src/testFixtures/resources`.

Like for the source set of a project the test fixtures sets are defined as full JPMS modules, too. Based on that
a `module-info.java` file must be placed directly under `src/testFixtures/java` if at least one Java file is present.
The name of the test fixtures module should be based on the name of the source module and add a `test.fixtures` suffix.
For the given sample of the foo service the name and the base package for the test fixtures set would
be `com.hedera.node.app.services.foo.test.fixtures`. Since the test fixtures JPMS module will only be used in tests it
should be fully opened. This makes the content of the `module-info.java` much more readable since no individual `opens`
statements need to be added. The complete module will be opened by adding the `open` keyword directly to the module
definition:

```
open module com.hedera.node.app.services.foo.test.fixtures {
    //...
}
```

**Hint:** Similar to any other module the test fixtures set of a module can be added as a dependency by using the
corresponding `requires`. In this sample: `requires com.hedera.node.app.services.foo.test.fixtures`
(in module-info.java) or `testModuleInfo { requires("com.hedera.node.app.services.foo.test.fixtures") }"`
(in build.gradle.kts for unit tests that have no module-info.java).

### Unit tests

For the unit test set the `src/test/java` and `src/test/resources` folders must be used. Unit tests will be executed on
the Java classpath and therefore no `module-info.java` is needed. This has the big benefit that unit tests can have the
same base package as the module sources. By doing so unit tests can access package private classes, constructors, ...

### Integration tests

For the integration test set the `src/testIntegration/java` and `src/testIntegration/resources` folders must be used.
All integration tests will be executed on the module path to be as near to the real usage as possible. Based on that a
`module-info.java` file is needed. Like for test fixtures the name for the module and the base package is based on the
name of the source module plus the 'test.integration' suffix. For the given sample the JPMS module name for the
integration tests would be `com.hedera.node.app.services.foo.test.integration`. Since the test fixtures JPMS module
will only be used in tests it should be fully opened. This makes the content of the `module-info.java` much more
readable since no individual `opens` statements need to be added. The complete module will be opened by adding the
`open` keyword directly to the module definition:

```
open com.hedera.node.app.services.foo.test.integration {
    //...
}
```

## JMH benchmarks

Next to tests a module can have several benchmarks to test the performance of critical components within the module. All
benchmarks must be based on JMH and the `src/jmh/java` and `src/jmh/resources` folders must be used.

## Basic module template

Based on the given definitions a module folder in the project looks like this:

```
foo-service/
├── src/main/java/
│   ├── com.hedera.node.app.service.foo
│   │   ├── FooService.java
│   │   └── package-info.java
│   └── module-info.java
├── src/main/resources/
│   ├── com.hedera.node.app.service.foo
│   │   └── some_data.json
│   └── logging.properties
├── src/testFixtures/java/
│   ├── com.hedera.node.app.service.foo.testfixtures
│   │   └── FooServiceTestConfig.java
│   └── module-info.java
├── src/test/java/
│   └── com.hedera.node.app.service.foo
│       └── FooServiceTest.java
├── src/testIntegration/java/
│   ├── com.hedera.node.app.service.foo.itest
│   │   └── FooServiceITest.java
│   └── module-info.java
└── build.gradle.kts
```

### Open questions:

The following questions are currently not answered by the doc

- Should it be `src/eet/java` or `src/eetest/java` or `src/e2e/java`?
- Should e-2-e tests run on the classpath or the module path?
- Should benchmarks run on the classpath or the module path?
