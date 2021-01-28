import dev.w1zzrd.asm.Injector;
import java.io.IOException;

public class Test {
    public static void main(String... args) throws IOException {
        // Load target class, inject all annotated classes and load compiled bytecode into JVM
        Injector.injectAll("MergeTest").compile();

        // Run simple injection tests
        new MergeTest().test();

        // Run test of more complex stack arrangement
        System.out.println(new MergeTest().stackTest());

        // Injected interface
        Runnable r = (Runnable) new MergeTest();
        r.run();
    }

}
