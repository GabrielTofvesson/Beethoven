package dev.w1zzrd.asm;

import dev.w1zzrd.asm.analysis.AsmAnnotation;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.AnnotationNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Objects;

/**
 * Simple class for automatically performing transformations
 */
public class Injector {
    private static final String injectAnnotDesc = "L"+InjectClass.class.getName().replace('.', '/')+";";

    /**
     * Attempt to inject all valid classes into the given merger from the given loader
     * @param loader Loader to get class resources from
     * @param merger Merger to inject resources into
     * @throws IOException If any resource could not be loaded properly
     */
    public static void injectAll(ClassLoader loader, Combine merger) throws IOException {
        Enumeration<URL> resources = loader.getResources("");
        while (resources.hasMoreElements())
            injectDirectory(new File(resources.nextElement().getPath()), merger);
    }

    // Inject all files in a given directory into the merger
    private static void injectDirectory(File file, Combine merger) throws IOException {
        if (file.isDirectory())
            for (File child : Objects.requireNonNull(file.listFiles()))
                injectDirectory(child, merger);
        else injectFile(file, merger);
    }

    // Inject file into a given merger (if declared as such)
    private static void injectFile(File file, Combine merger) throws IOException {
        URL url = null;
        try {
            url = file.toURI().toURL();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        ClassNode cNode;

        assert url != null;
        if(url.getPath().endsWith(".class") && shouldInject(merger, cNode = Loader.getClassNode(url))) {
            GraftSource source = new GraftSource(cNode);
            for (MethodNode mNode : source.getInjectMethods())
                merger.inject(mNode, source);
        }
    }

    private static boolean shouldInject(Combine combine, ClassNode node) {
        for (AnnotationNode annotNode : node.visibleAnnotations)
            if (annotNode.desc.equals(injectAnnotDesc) &&
                    ((Type)(AsmAnnotation.getAnnotation(annotNode).getEntry("value")))
                            .getClassName()
                            .equals(combine.getTargetName()))
                return true;

        return false;
    }
}
