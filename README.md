# Beethoven: A library for making Java bytecode instrumentation easier

The point of this library is to offer a simple, annotation-driven way to instrument classes.
What this means is that a user of this library should never have to see
so much as a byte. Everything should be abstracted to the level of classes,
methods and fields.

## Version 2

### Implemented

* Per-instruction method frame state analysis tools

* Method insertion

* Method replacement
  
* In-place method appending
  * Accepting return value as argument
  * Overwriting return value

* In-place method prepending
  
* Automatic injection of [INVOKEDYNAMIC](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.invokedynamic) lambda targets

### TODO

* Better tests

* Execution path optimization (e.g. remove unnecessary [GOTO](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.goto)s)

* Implement exceptions

* Implement try-catch
  * Implement subroutines ([JSR](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.jsr) / [RET](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.ret))