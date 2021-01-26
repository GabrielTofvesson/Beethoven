import dev.w1zzrd.asm.Combine;
import dev.w1zzrd.asm.GraftSource;
import dev.w1zzrd.asm.Merger;
import dev.w1zzrd.asm.analysis.FrameState;
import dev.w1zzrd.asm.signature.TypeSignature;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Stack;

public class Test {
    public static void main(String... args) throws IOException {

        ClassNode target = Merger.getClassNode("MergeTest");
        ClassNode inject = Merger.getClassNode("MergeInject");

        MethodNode stackTest = target.methods.stream().filter(it -> it.name.equals("stackTest")).findFirst().get();

        Stack<TypeSignature> stack = FrameState.getFrameStateAt(stackTest.instructions.getLast().getPrevious().getPrevious().getPrevious().getPrevious().getPrevious(), stackTest.localVariables);

        GraftSource source = new GraftSource(inject);

        Combine combine = new Combine(target);
        for (MethodNode method : source.getInjectMethods()) {
            combine.inject(method, source);
        }

        File f = new File("MergeTest.class");
        if(f.isFile() && f.delete() && f.createNewFile()) {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(combine.toByteArray());
            fos.close();
        }

        combine.compile();

        new MergeTest().test();

        System.out.println(new MergeTest().stackTest());

        /*
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
        */
    }

}
