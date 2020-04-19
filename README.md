# Beethoven: A library for making Java bytecode instrumentation easier

The point of this library is to offer a simple, annotation-driven way to instrument classes.
What this means is that a user of this library should never have to see
so much as a byte. Everything should be abstracted to the level of classes,
methods and fields.

## Progress:

* Method injection (replacement, in-place leading injection and in-place trailing injection)

* Field injection (access transforming and new fields)

* Target field accesses from injection class (non-primitive/reference)

* Method calls and field accesses referencing injection class elements automatically redirected to target

* Optional interface injection

* Target class method invocation

* Multiple injections per method

* In-place method instruction injection with references to previous method's return value

## TODO:

* Better tests
