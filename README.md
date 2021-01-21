# Beethoven: A library for making Java bytecode instrumentation easier

The point of this library is to offer a simple, annotation-driven way to instrument classes.
What this means is that a user of this library should never have to see
so much as a byte. Everything should be abstracted to the level of classes,
methods and fields.

## Version 2

### Implemented

* Per-instruction method frame state analysis tools

* Method insertion (basic)

* Method replacement (basic)

### TODO
  
* In-place method prepending

* In-place method appending

* Automatic injection of INVOKEDYNAMIC lambda targets