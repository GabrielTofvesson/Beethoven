package dev.w1zzrd.asm;

import jdk.internal.org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Objects;

public class Injector {
    public static void injectAll(ClassLoader loader, Merger merger) throws IOException {
        Enumeration<URL> resources = loader.getResources("");
        while (resources.hasMoreElements())
            injectDirectory(new File(resources.nextElement().getPath()), merger);
    }

    private static void injectDirectory(File file, Merger merger) throws IOException {
        if (file.isDirectory())
            for (File child : Objects.requireNonNull(file.listFiles()))
                injectDirectory(child, merger);
        else injectFile(file, merger);
    }

    private static void injectFile(File file, Merger merger) throws IOException {
        URL url = null;
        try {
            url = file.toURI().toURL();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        ClassNode cNode;

        assert url != null;
        if(url.getPath().endsWith(".class") && merger.shouldInject(cNode = Merger.getClassNode(url)))
            merger.inject(cNode);
    }
}
