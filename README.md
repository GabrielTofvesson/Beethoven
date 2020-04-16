# Beethoven: A library for making Java bytecode instrumentation easier

The point of this library is to offer a simple, annotation-driven way to instrument classes.
What this means is that a user of this library should never have to see
so much as a byte. Everything should be abstracted to the level of classes,
methods and fields.

## Progress:

* Injected methods

* Target field accesses from injection class (non-primitive/reference)

* Method calls and field acceses referencing injection class elements automatically redirected to target

* Optional interface injection

## TODO:

* Target class method invocation

* Support for target class primitive field access

* Support for target class primitive return-value methods

* Multiple injections per method

* In-place method instruction injection

* Better tests
