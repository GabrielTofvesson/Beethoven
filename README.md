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
  
* Inject superclasses

* Inject interfaces

* Inject fields

* Method injection priority

## How do I use this?

Beethoven is designed to be relatively accessible, so you should never have to see raw JVM instructions. Let's use an
example:

Given the following class:
```java
public final class Foo {
  private final int myNumber;

  public Foo(int myNumber) {
    this.myNumber = myNumber;
  }

  public int addMyNumber(int addTo) {
    return addTo + this.myNumber;
  }
}
```

Say we would like to modify the behaviour of `addMyNumber` to print the value of `addTo` and return the value as defined
above, except multiplied by two. Under normal conditions where the `Foo` source code could be modified, this might looks
as follows:

```java
public int addMyNumber(int addTo) {
  System.out.println(addTo);
  return (addTo + this.myNumber) / 2;
}
```

But if we for some reason cannot change the source code of `Foo`, we can instead use Beethoven to modify the bytecode at
runtime by defining a class which specifies how we would like to modify the behaviour. In our example, this would look
as follows:

```java
import dev.w1zzrd.asm.InPlaceInjection;
import dev.w1zzrd.asm.Inject;

public class TweakFoo {
  @Inject(value = InPlaceInjection.AFTER, target = "addMyNumber(I)I", acceptOriginalReturn = true)
  public int addMyNumber(int addTo, int ret) {
    System.out.println(addTo);
    return ret / 2;
  }
}
```

We specify using the `@Inject` annotation that we would like to inject the annotated method into the targeted class (
we'll get to how we target a class in a sec), that we would like to append the code `AFTER` the existing code, that we
are targeting a method named `addMyNumber` which accepts an int (specified by `(I)`) and returns an int (specified by
the final `I`), as well as that we would like to accept the original method's return value as an argument to the tweak
method (named `ret` in the example code above). If we hadn't cared about what the original method returned, we could
simply ignore the `acceptOriginalReturn` parameter and omit the `int ret` argument from the method.

### Type signatures

Method signatures, as used in the example code above (the `target` value in the `@Inject` annotation), are specified in
the following format: `methodName(argumentTypes)returnType`

Types follow the JNI type naming standard:

| Type | Name |
| :--- | :---: |
| Boolean | Z |
| Byte | B |
| Short | S |
| Int | I |
| Long | J |
| Float | F |
| Double | D |
| Object | L`type`; |

For objects, the type is specified as the full path of the type, where all `.` are replaced with `/`. So, for example,
a Java string would be written as `Ljava/lang/String;`. Generics are ignored when describing an objects type.

Finally, this means that, for example, the method
```java
public Class<?> getClass(String name, int index, float crashProbability) {
    // ...
}
```
would have the `target` value `getClass(Ljava/lang/String;IF)Ljava/lang/Class;`

### Targeting classes

To target a class, simply create an instance of the `dev.w1zzrd.asm.Combine` class targeting it. After that, you feed it
annotated MethodNodes. For example, using the example code described earlier, we could do as follows:

```java
import dev.w1zzrd.asm.Combine;
import dev.w1zzrd.asm.GraftSource;
import dev.w1zzrd.asm.Loader;

public class Run {
  public static void main(String[] args) {
    // This is the class we would like to inject code into
    Combine target = new Combine(Loader.getClassNode("Foo"));

    // This is the class we want to inject code from
    GraftSource source = new GraftSource(Loader.getClassNode("TweakFoo"));
    
    // Inject all annotated methods
    for (MethodNode toInject : source.getInjectMethods()) {
      // Inject an annotated method into the targeted class
      target.inject(toInject, source);
    }
    
    // Now we load the tweaked Foo class into the JVM
    target.compile();
    
    // Done! To see that it has worked, we can try running the tweaked method:
    int result = new Foo(5).addMyNumber(15);  // Prints "5"
    
    System.out.println(result); // Prints "10"
  }
}
```

To inject code from multiple tweak classes, simply define more `GraftSource` instances and inject them into the target.

Currently, Beethoven supports injecting code before and after the original method, as well as overwriting existing
methods entirely, as well as adding new methods.