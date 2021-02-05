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

* Method injection priority
  
* Inject superclasses

* Inject interfaces

* Inject fields

* Handle exceptions

* Inject try-catch

### TODO

* Better tests

* Execution path optimization (e.g. remove unnecessary [GOTO](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.goto)s)
  
* ~~Implement subroutines ([JSR](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.jsr) / [RET](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.ret))~~

*Note: Subroutines are prohibited as of class file version 51, so unless this project sees much use with projects targeting Java
version 1.6 or lower on very specific compilers, this implementation can be set aside until everything else is polished.*

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
above, except divided by two. Under normal conditions where the `Foo` source code could be modified, this might looks
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

If this looks a bit confusing: don't worry, [it gets simpler](#a-simple-example) after we remove all the things that can
be inferred automatically by the library. We start with a more complicated example to show explicitly what's happening
under the hood in case it's needed in more complex use-cases.

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
| Object | L`path`; |
| Array | [`Type` |

For objects, the type is specified as the full path of the type, where all `.` are replaced with `/`. So, for example,
a Java string would be written as `Ljava/lang/String;`. Generics are ignored when describing an objects type.

Array types are defined as a `[` followed by any JNI type (including another array type). Nested arrays are written as
consecutive `[`. For example, a three-dimensional `Throwable` array (`Throwable[][][]`) would be written as
`[[[Ljava/lang/Throwable;`.

Finally, this means that, for example, the method
```java
public Class<?> getClass(
        String name,
        int index,
        float crashProbability,
        double[] numbers,
        String[][] crashMap
        ) {
    // ...
}
```
would have the `target` value `getClass(Ljava/lang/String;IF[D[[Ljava/lang/String;)Ljava/lang/Class;`

### Targeting classes

To target a class, simply create an instance of the `dev.w1zzrd.asm.Combine` class targeting it. After that, you feed it
annotated MethodNodes. For example, using the example code described earlier, we could do as follows:

```java
import dev.w1zzrd.asm.*;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

public class Run {
  public static void main(String[] args) throws Exception {
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

In fact, there is a drastically simpler way to inject code into a target. Say we know that the above classes are all
available to the default ClassLoader's classpath. In this case, we can simply do as follows:

```java
import dev.w1zzrd.asm.Injector;

public class Run {
  public static void main(String[] args) throws Exception {
      // Locates all necessary tweaks and injects them into Foo
    Injector.injectAll("Foo").compile();

    // Done! To see that it has worked, we can try running the tweaked method:
    int result = new Foo(5).addMyNumber(15);  // Prints "5"

    System.out.println(result); // Prints "10"
  }
}
```

The only caveat is that classes injected this way must have an `@InjectClass` annotation declaring the targeted class.
In our example, this would mean that the `TweakFoo` class would look as follows:

```java
import dev.w1zzrd.asm.*;

// This marks the class as targeting Foo
@InjectClass(Foo.class)
public class TweakFoo {
  @Inject(value = InPlaceInjection.AFTER, target = "addMyNumber(I)I", acceptOriginalReturn = true)
  public int addMyNumber(int addTo, int ret) {
    System.out.println(addTo);
    return ret / 2;
  }
}
```

Additionally, method resolution is relatively intelligent, so one can omit the `target` parameter of the `@Inject`
annotation in cases where the target is unambiguous. As long as the tweak method has the same name as the targeted
method, the target should never be ambiguous. In fact, the resolution is intelligent enough that if the method signature
is unambiguous for the targeted method name in the targeted class, the `acceptOriginalReturn` value can even be omitted.
This means that a minimal example implementation of the `TweakFoo` class could look as follows:

#### A simple example:
```java
import dev.w1zzrd.asm.*;
import static dev.w1zzrd.asm.InPlaceInjection.AFTER;

@InjectClass(Foo.class)
public class TweakFoo {
  @Inject(AFTER)
  public int addMyNumber(int addTo, int ret) {
    System.out.println(addTo);
    return ret / 2;
  }
}
```

### Priority

In the case where multiple injections are to be made into one method (e.g. `BEFORE` and `AFTER`), it may be useful to
have a clear order in which the injections should occur. As such, the `priority` value of an `@Inject` annotation can be
passed to specify which value to inject first. Methods with a lower priority value will be injected earlier than ones
with higher values. By default, methods have a priority of hex 0x7FFFFFFF (maximum int value). Methods with the same
target and same priority have no guarantees on injection order. I.e. if two methods annotated with `@Inject` target the
same method and declare the same priority, there are no guarantees on which one will be injected first. Thus, if a
method is targeted for injection by multiple tweak methods, it is highly recommended that an explicit priority be
declared for at least all except one tweak method.