package io.kubefn.runtime.classloader;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Per-group classloader that enables hot-swap and born-warm deploys.
 *
 * <p>Parent-first for platform API (io.kubefn.api.*, java.*, org.slf4j.*).
 * Child-first for everything else (function classes and their deps).
 *
 * <p>Discarding this classloader unloads the entire function group,
 * enabling hot-reload without JVM restart.
 */
public class FunctionGroupClassLoader extends URLClassLoader {

    private static final String[] PARENT_FIRST_PREFIXES = {
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "io.kubefn.api.",       // Platform API — always from parent
            "org.slf4j.",           // Logging facade — always from parent
    };

    private final String groupName;

    public FunctionGroupClassLoader(String groupName, URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.groupName = groupName;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // Check if already loaded
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }

            // Parent-first for platform packages
            for (String prefix : PARENT_FIRST_PREFIXES) {
                if (name.startsWith(prefix)) {
                    return super.loadClass(name, resolve);
                }
            }

            // Child-first: try our own classes first (function code)
            try {
                Class<?> c = findClass(name);
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            } catch (ClassNotFoundException e) {
                // Fall back to parent
                return super.loadClass(name, resolve);
            }
        }
    }

    public String groupName() {
        return groupName;
    }

    @Override
    public String toString() {
        return "FunctionGroupClassLoader[" + groupName + "]";
    }
}
