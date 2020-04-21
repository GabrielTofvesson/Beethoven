import dev.w1zzrd.asm.Merger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class Test {
    public static void main(String... args) throws IOException {
        Merger m = new Merger("MergeTest");
        m.inject("MergeInject");

        // Save injected data
        File f = new File("out.class");
        if(f.isFile() && f.delete() && f.createNewFile()) {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(m.toByteArray());
            fos.close();
        }

        // Compile and run
        m.compile();

        Runnable r = (Runnable)new MergeTest("Constructor message");
        r.run();
    }
}
