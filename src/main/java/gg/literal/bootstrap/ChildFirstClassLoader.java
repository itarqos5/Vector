package gg.literal.bootstrap;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

public final class ChildFirstClassLoader extends URLClassLoader {

    private final List<String> childFirstPrefixes;

    public ChildFirstClassLoader(final URL[] urls, final ClassLoader parent, final List<String> childFirstPrefixes) {
        super(urls, parent);
        this.childFirstPrefixes = childFirstPrefixes;
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            for (String prefix : childFirstPrefixes) {
                if (name.startsWith(prefix)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException ignored) {
                            loaded = super.loadClass(name, false);
                        }
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
