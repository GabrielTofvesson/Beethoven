package dev.w1zzrd.asm;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;

public class Loader {

    /**
     * Get a glass node from a given resource
     * @param url Resource to load class node from
     * @return Class node loaded from the resource
     * @throws IOException If the resource cannot be loaded
     */
    public static ClassNode getClassNode(URL url) throws IOException {
        return readClass(getClassBytes(url));
    }

    /**
     * Read class data to a class node
     * @param data Bytecode to read
     * @return Class node read
     */
    public static ClassNode readClass(byte[] data) {
        ClassNode node = new ClassNode();
        new ClassReader(data).accept(node, 0);
        return node;
    }

    /**
     * Read a class node from a given class
     * @param name Name of the class to get the class node from
     * @return Loaded class node
     * @throws IOException If the class data resource cannot be loaded
     */
    public static ClassNode getClassNode(String name) throws IOException {
        return readClass(getClassBytes(name));
    }

    /**
     * Read a class node from a given class
     * @param name Name of the class to get the class node from
     * @param loader Loader to use when loading the class resource
     * @return Loaded class node
     * @throws IOException If the class data resource cannot be loaded
     */
    public static ClassNode getClassNode(String name, ClassLoader loader) throws IOException {
        return readClass(getClassBytes(name, loader));
    }

    /**
     * Get class bytecode for a given class
     * @param name Name of the class to get data for
     * @return Bytecode for the requested class
     * @throws IOException If the class data resource cannot be loaded
     */
    public static byte[] getClassBytes(String name) throws IOException {
        return getClassBytes(name, ClassLoader.getSystemClassLoader());
    }

    /**
     * Get class bytecode for a given class
     * @param name Name of the class to get data for
     * @param loader Loader to use when loading the class resource
     * @return Bytecode for the requested class
     * @throws IOException If the class data resource cannot be loaded
     */
    public static byte[] getClassBytes(String name, ClassLoader loader) throws IOException {
        return getClassBytes(Objects.requireNonNull(loader.getResource(name.replace('.', '/') + ".class")));
    }

    /**
     * Get class bytecode for a given class
     * @param url Resource to load class data from
     * @return Bytecode for the requested class resource
     * @throws IOException If the class data resource cannot be loaded
     */
    public static byte[] getClassBytes(URL url) throws IOException {
        InputStream stream = url.openStream();
        byte[] classData = new byte[stream.available()];

        int total = 0;
        do total += stream.read(classData, total, classData.length - total);
        while (total < classData.length);

        return classData;
    }
}
