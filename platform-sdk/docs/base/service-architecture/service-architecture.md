[⇧ Platform Wiki](../../platformWiki.md)

## Service architecture

The main goal of the base modules is not to provide static util functions.
Instead, the modules should provide services that can be used by other modules.
The services should be well documented and tested.
The services should be easy to use and should provide a clear API.

To achieve this goal we defined several patterns that we use in the base modules to define services.

**Note:** We use the term 'service' in this documentation to describe a class that provides a specific functionality.
Others might be more familiar with the terms like 'manager', 'handler' or 'provider'.
Even in our code we use different terms but in this documentation we use the term 'service' for consistency.

### Providing a public service api

The public service api should be defined in a separate package.
This package should contain only interfaces, annotations, records and classes that are part of the public api.
Let's assume we have the service `FooService` that provides a method `doSomething`.
The public api should look like this:

```java
package com.swirlds.base.foo;

/**
* This is the service that provides the foo functionality.
*/
public interface FooService {

    /**
    * This method does something.
    */
    void doSomething();
}
```

The implementation of the service should be in a separate package that is handled as private api.

### Separating public and private api

The private api should be defined in a separate package or module.
Here it depends on the use case of the service what pattern fits best.
The most easy way is to just split the public and private api in 2 packages.
In the most easy way the split can be as simple as the following sample:

```java
com.swirlds.base.foo.FooService
com.swirlds.base.foo.internal.FooServiceImpl
```

Here we define the sub package internal that contains the private implementation of the service.
That package should never be exported by the module.

If we assume that a different implementation of the service is possible, the private api should be in a separate module.
Next to that it might be possible that the private api needs dependencies that are not needed in the public api.
In all that cases a split in 2 modules is the best way to go.
If that case we use the Java SPI pattern to define the service.
Even if a services is started in 1 module the split in 2 modules can easily be done in future.
The following sample shows how the split in 2 modules can be done:

```java

// Module foo-api
module com.swirlds.base.foo.api {
    exports com.swirlds.base.foo;
    uses com.swirlds.base.foo.FooService;
}

// Module foo-impl
module com.swirlds.base.foo.impl {
    requires com.swirlds.base.foo.api;
    provides com.swirlds.base.foo.FooService with com.swirlds.base.foo.internal.FooServiceImpl;
}
```

Note: Currently, we do not always use the Java Module System at runtime and 3rdParty apps may continue to use the
base modules in the future on the classpath. Therefore, service providers are additionally registered via files located
in `META-INF/services` in the assembled Jar files. These files are automatically generated during the Gradle build
(`generateMetaInfServices` task).

### Providing a service factory

Since our services are always managed instances we provide a factory or factory method for each service as part of the public api.
For a simple service the most easy way is to provide a factory method in the service interface.
The following sample shows how the factory method can be defined:

```java
package com.swirlds.base.foo;

/**
* This is the service that provides the foo functionality.
*/
public interface FooService {

    /**
    * This method does something.
    */
    void doSomething();

    /**
    * This method creates a new instance of the service.
    */
    static FooService create() {
        return new FooServiceImpl();
    }
}
```

As you can see the factory method is a static method that creates a new instance of the service.
By doing so the factory method can be used to create a new instance of the service without knowing the implementation of the service.
Next to that it is important to define the scope of the service.
In the given sample a new instance is created each time the factory method is called.
For some other services it might be useful to create a singleton instance of the service.
In that case the factory method should be defined as follows:

```java
package com.swirlds.base.foo;

/**
* This is the service that provides the foo functionality.
*/
public interface FooService {

    /**
    * This method does something.
    */
    void doSomething();

    /**
    * This method returns a singleton instance of the service.
    */
    static FooService getInstance() {
        return FooServiceImpl.getInstance();
    }
}
```

In the given sample the service implementation is a singleton and the factory method returns the singleton instance.
It makes sense to give a name to the factory method that contains information about the scope of the service.

While this code is easy to understand and to use a more complex factory pattern might be needed.
Maybe we assume that the service implementation or even scope changes in future.
As already mentioned we favor the Java spi pattern to define services in that case.
Here it makes sense to define a factory interface that is part of the public api.
The Java spi is than used to load a concrete factory and use it to get or create a service instance.
The following sample shows how the factory interface can be defined:

```java
package com.swirlds.base.foo;

/**
* This is the factory that creates instances of the foo service.
*/
public interface FooServiceFactory {

    /**
    * Returns an instance of the service.
    */
    FooService getOrCreate();

    /**
    * Returns the real factory.
    */
    FooServiceFactory getFactory() {
        return SpiLoader.load(FooServiceFactory.class);
    }
}
```

The factory interface defines a method `getOrCreate` that returns an instance of the service.
Next to that the factory interface defines a method `getFactory` that returns the real factory.
The real factory is loaded by the `SpiLoader` that is part of the base modules.
The `SpiLoader` is a simple class that uses the Java spi to load a concrete factory.

### Defining a service as a singleton

As mentioned before it might be useful to define a service as a singleton.
In that case the service implementation should be a singleton and the factory method should return the singleton instance.
We defined a simple pattern to define a service as a singleton.
That pattern is used in the base modules at all new services and old services will be refactored in future to use that pattern.
The pattern is defined as follows:

```java
package com.swirlds.base.foo.internal;

import com.swirlds.base.foo.FooService;

/**
* This is the service that provides the foo functionality.
*/
public class FooServiceImpl implements FooService {

    private static class InstanceHolder {
        private static final FooService INSTANCE = new FooServiceImpl();
    }

    private FooServiceImpl() {
        // private constructor
    }

    public static FooServiceImpl getInstance() {
        return InstanceHolder.INSTANCE;
    }

    @Override
    public void doSomething() {
        // do something
    }
}
```

The service implementation is a singleton and the singleton instance is created in a static inner class.
The singleton instance is created when the class is loaded and is thread safe.
The singleton is created lazy and is only created when the `getInstance` method is called the first time.
