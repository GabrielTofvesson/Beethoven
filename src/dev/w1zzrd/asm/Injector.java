package dev.w1zzrd.asm;

import dev.w1zzrd.asm.analysis.AsmAnnotation;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Objects;

/**
 * Simple class for automatically performing transformations
 */
public class Injector {
    /**
     * Attempt to inject all valid classes into the given merger from the given loader
     * @param loader Loader to get class resources from
     * @param merger Merger to inject resources into
     * @throws IOException If any resource could not be loaded properly
     */
    public static void injectAll(ClassLoader loader, Combine merger) throws IOException {
        Enumeration<URL> resources = loader.getResources("");
        while (resources.hasMoreElements())
            injectDirectory(new File(URLDecoder.decode(
                    resources.nextElement().getFile(),
                    StandardCharsets.UTF_8.name())
            ), merger);
    }

    public static void injectAll(Combine merger) throws IOException {
        injectAll(ClassLoader.getSystemClassLoader(), merger);
    }

    public static Combine injectAll(ClassLoader loader, String name) throws IOException {
        Combine combine = new Combine(Loader.getClassNode(name));
        injectAll(loader, combine);
        return combine;
    }

    public static Combine injectAll(String name) throws IOException {
        return injectAll(ClassLoader.getSystemClassLoader(), name);
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

        AsmAnnotation<InjectClass> annot;

        if(url.getPath().endsWith(".class") &&
                shouldInject(merger,
                        (annot = AsmAnnotation.getAnnotation( // Load InjectClass annotation (if it exists)
                                InjectClass.class,
                                (cNode = Loader.getClassNode(url)).visibleAnnotations // Load class data
                        )))) {
            GraftSource source = new GraftSource(cNode);
            for (MethodNode mNode : source.getInjectMethods())
                merger.inject(mNode, source);

            for (FieldNode fNode : source.getInjectFields())
                merger.inject(fNode, source);

            if ((Boolean)annot.getEntry("injectInterfaces")) {
                for (String iface : cNode.interfaces)
                    merger.addInterface(iface);
            }
        }
    }

    private static boolean shouldInject(Combine combine, AsmAnnotation<InjectClass> annot) {
        return annot != null && ((Type) annot
                .getEntry("value"))
                .getClassName()
                .equals(combine.getTargetName());
    }
}
