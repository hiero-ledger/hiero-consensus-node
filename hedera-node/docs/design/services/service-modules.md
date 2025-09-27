# Service modules architecture

**INFORMATION:** This document is a draft based on our current ideas. The document might change over time. Next to this
the described architecture might not be reflected directly in the code. Once the service architecture has been finalized
the description inn this documentation must fit to the code.

All services are seperated in an api module and an implementation module. The name pattern for the api modules
is `hedera-node/hedera-[NAME]-service` and the name pattern for the modules that contain the implementation
is `hedera-node/hedera-[NAME]-service-impl`. All modules support the Java module system. In the following sample `'Foo'`
/`'foo'` is used as placeholder for the service name.

## Service api modules

All service api modules are based on the same structure and a minimal module looks like this:

```
hedera-foo-service/
├── src/main/java/
│   ├── com.hedera.node.app.service.foo
│   │   ├── FooService.java
│   │   └── package-info.java
│   └── module-info.java
└── build.gradle.kts
```

The api modules should depend on the `com.hedera.node.app.spi` module and provide it as a transitive dependency. To do
so `requires transitive com.hedera.node.app.spi` must be a dependency in the `module-info.java` file. The module should
only contain the public api of services. The complete content of the module should be exported. Since the service
interface that are defined in the api modules will be loaded by the Java SPI the interfaces must be defined in the
module info by using the `uses` keyword.

Based on this given constraints the `module-info.java` of an api module looks like this:

```
module com.hedera.node.app.service.foo {
    exports com.hedera.node.app.service.foo;

    uses com.hedera.node.app.service.foo.FooService;

    requires transitive com.hedera.node.app.spi;
}
```

Next to this the `build.gradle.kts` file should look like this:

```
plugins {
    id("com.hedera.gradle.services")
}

description = "Hedera Foo Service API"
```

The public service api can depend on additional libraries. Such libraries will be defined as **public api** and must be
added as `requires transitive` in the `module-info.java`. No api module
must ever depend on any service implementation or the `hedera-mono-service` module. The `@NonNull`/`@Nullable`
annotations can be used in the service definitions. Since the annotations are already defined as transitive dependencies
at compile time by the `hedera-app-spi` module no extra dependency needs to be added. Unit tests and integration tests
are normally part of the implementation modules but the api modules can contain such test when it makes sense. In such
case the same definition as for the implementation modules should be used.

Since all services must be accessed by the Java SPI we need to define an entry-point in the api module. Here we use a
pattern that defines the entry point directly as a static method in the service interface:

```
package com.hedera.node.app.service.foo;

import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.ServiceFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ServiceLoader;

public interface FooService extends RpcService {

    @NonNull
    @Override
    default String getServiceName() {
        return FooService.class.getSimpleName();
    }

    /**
     * Returns the concrete implementation instance of the service
     *
     * @return the implementation instance
     */
    @NonNull
    static FooService getInstance() {
        return ServiceFactory.loadService(FooService.class, ServiceLoader.load(FooService.class));
    }
}
```

The basic `Service` interface and the `ServiceFactory` class that provides the SPI implementation are part of
the `hedera-app-spi` module.

## service implementation modules

For each api module an additional module that contains the implementation must be provided. The structure of a minimal
implementation module (imp-module) looks like this:

```
hedera-foo-service-impl/
├── src/main/java/
│   ├── com.hedera.node.app.service.foo.impl
│   │   ├── FooServiceImpl.java
│   │   └── package-info.java
│   └── module-info.java
├── src/itest/java/
│   ├── com.hedera.node.app.service.foo.impl.itest
│   │   ├── FooServiceImplITest.java
│   └── module-info.java
├── src/test/java/
│   └── com.hedera.node.app.service.foo.impl
│       └── FooServiceImplTest.java
└── build.gradle.kts
```

The impl-module depends on the corresponding api module. Next to this the module currently often needs to depend on the
`hedera-mono-service` module. Once the modularization of the project is done the `hedera-mono-service` module will be
removed. If a service implementation depends on another service it must always depend on the api of that service and
never on another impl-module. All service access (excluding unit tests) must be done by using the Java SPI. An
impl-module can use other dependencies that are needed for the implementation. Such dependencies should never be exposed
and defined as `implementation` dependencies in gradle.

Based on the given definitions and constrains a minimalistic `build.gradle.kts` looks like this:

```
plugins {
    id("com.hedera.gradle.services")
}

description = "Default Hedera Foo Service Implementation"

dependencies {
    api(project(":hedera-node:hedera-foo-service"))
    implementation(project(":hedera-node:hedera-mono-service")) //will be removed in future
}
```

Since all implementation of services should be handled as private API an impl-module must never expose any packages.
Only the tests (unit tests & integrations tests) of the impl-module can access the packages. In some rare cases external
tests might need access. Such usecase should be discussed and handled individually. Normally a refactoring is the better
solution for such issue.

The `module-info.java` must define the implementation of the service api by using the `provides` keyword. A
minimalistic `module-info.java` looks like this:

```
module com.hedera.node.app.service.foo.impl {
    requires transitive com.hedera.node.app.service.foo;

    provides com.hedera.node.app.service.foo.FooService with
             com.hedera.node.app.service.foo.impl.FooServiceImpl;

    exports com.hedera.node.app.service.foo.impl to
            com.hedera.node.app.service.foo.impl.itest;
}
```

Since all integration tests must be executed on the module path a `module-info.java` file must be placed
under `src/itest/java`. A minimal `module-info.java` for integration tests looks like this:

```
open module com.hedera.node.app.service.foo.impl.itest {
    requires com.hedera.node.app.service.foo.impl;
    requires org.junit.jupiter.api;
}
```

Each impl-module must contain an integration test that checks that the service implementation will be loaded by the Java
SPI:

```
package com.hedera.node.app.service.foo.impl.itest;

import com.hedera.node.app.service.foo.FooService;
import com.hedera.node.app.service.foo.impl.FooServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FooServiceImplTest {

    @Test
    void testSpi() {
        // when
        final FooService service = FooService.getInstance();

        // then
        Assertions.assertNotNull(service, "We must always receive an instance");
        Assertions.assertEquals(
                FooServiceImpl.class,
                service.getClass(),
                "We must always receive an instance of type " + FooServiceImpl.class.getName());
    }
}
```

The functionality of service implementations should be tested in unit tests. Since unit tests are executed on the
classpath no `module-info.java` is needed and the tests can be in the same package as the implementations.

## Using services

Normally there should only be 2 places where a service should be used:
in the impl-module of another service and in the `hedera-app` module. In both cases the configuration and entrypoint is
the same. The dependency to the impl-module of a service must always be defined as runtime-only dependency. At
compile-time a project/module must only depend on the api module of a service. A minimal gradle configuration can look
like this:

```
dependencies {
    implementation(project(":hedera-node:hedera-foo-service"))
    runtimeOnly(project(":hedera-node:hedera-foo-service-impl"))
}
```

Since all services api should be defined on the same pattern a service instance can easily be accessed without any
programmatic usage of the implementation:

```
final FooService service = FooService.getInstance();
```

The Java SPI will automatically provide an instance of the service at runtime by creating a new `FooServiceImpl`
instance.
