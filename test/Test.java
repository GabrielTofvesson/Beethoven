import dev.w1zzrd.asm.Combine;
import dev.w1zzrd.asm.Injector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class Test {
    public static void main(String... args) throws IOException {
        ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(false);

        // Load target class, inject all annotated classes and load compiled bytecode into JVM
        dumpFile(Injector.injectAll("MergeTest"), "MergeTest").compile();

        // Run simple injection tests
        new MergeTest().test();

        // Run test of more complex stack arrangement
        System.out.println(new MergeTest().stackTest());

        // Injected interface
        Runnable r = (Runnable) new MergeTest();
        r.run();
    }

    public static Combine dumpFile(Combine comb, String name) {
        File f = new File(name + ".class");
        try {
            if ((f.isFile() && !f.delete()) || !f.createNewFile())
                System.err.printf("Could not dump file %s.class%n", name);
            else {
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(comb.toByteArray());
                fos.close(); // Implicit flush if underlying stream is buffered
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return comb;
    }

}
