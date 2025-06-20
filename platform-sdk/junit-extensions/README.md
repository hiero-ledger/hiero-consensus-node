# Junit Extensions

This module contains tailored made Junit Extensions for general use.

## List of available Extensions

* [ParameterCombinationExtension](src/main/java/org/hiero/junit/extensions/ParameterCombinationExtension.java)
  Annotations like @MethodSource allow specifying more than one method as input sources, but it doesn't allow
  individualizing each source method to a specific parameter, ParameterCombinationExtension solves tha problem by adding an
  executor that allows individually indicating source methods for each parameter in the test method.

  E.G.:

  ```java
  static Iterable<String> usernameSource() {
      return List.of("alice", "bob", "carol");
  }

  static Set<String> lastNameSource() {
      return Set.of("Anderson","Brown","Cooper");
  }

  @TestTemplate
  @ExtendWith(ParameterCombinationExtension.class)
  @UseParameterSources({
      @ParamSource(param = "username", method = "usernameSource"),
      @ParamSource(param = "lastName", method = "lastNameSource")
  })
  void testUser(@ParamName("username") String username, @ParamName("lastName") String lastName) {
      // This method will be executed for all combinations of usernames and ages.
      invokedParameters.add(new UsedParams(username, lastName, age));
  }
  ```

## Related to Java Module System at Runtime

Some of the extensions will need access to the module where is being instructed to execute code on:
Make sure to open the module to
`opensTo("org.hiero.junit.extensions")`
