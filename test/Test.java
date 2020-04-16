import dev.w1zzrd.asm.Merger;

import java.io.IOException;

public class Test {
    public static void main(String... args) throws IOException {
        Merger m = new Merger("MergeTest");
        m.inject(Merger.getClassNode("MergeInject"));
        m.compile();

        Runnable r = (Runnable)new MergeTest("Constructor message");
        r.run();
    }
}
