package com.aionemu.commons.scripting;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLStreamHandlerFactory;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.scripting.url.VirtualClassURLStreamHandler;
import com.aionemu.commons.utils.ClassUtils;

/**
 * Base class loader for runtime-compiled scripts.
 */
public abstract class ScriptClassLoader extends URLClassLoader {

    private static final Logger log = LoggerFactory.getLogger(ScriptClassLoader.class);

    private final Set<String> libraryClassNames = new HashSet<>();
    private final Set<File> loadedLibraries = new HashSet<>();
    private volatile VirtualClassURLStreamHandler urlStreamHandler;

    public ScriptClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public ScriptClassLoader(URL[] urls) {
        super(urls);
    }

    public ScriptClassLoader(URL[] urls, ClassLoader parent, URLStreamHandlerFactory factory) {
        super(urls, parent, factory);
    }

    public void addJarFile(File file) throws IOException {
        Objects.requireNonNull(file, "file");

        if (loadedLibraries.add(file)) {
            libraryClassNames.addAll(ClassUtils.getClassNamesFromJarFile(file));
        }
    }

    @Override
    public URL getResource(String name) {
        if (name == null || !name.endsWith(".class")) {
            return super.getResource(name);
        }

        String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
        if (getCompiledClasses().contains(className)) {
            try {
                URI uri = URI.create(VirtualClassURLStreamHandler.HANDLER_PROTOCOL + className);
                return URL.of(uri, getUrlStreamHandler());
            } catch (IllegalArgumentException | MalformedURLException e) {
                log.error("Can't create virtual URL for compiled class {}", className, e);
            }
        }

        return super.getResource(name);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        if (!getCompiledClasses().contains(name)) {
            return super.loadClass(name);
        }

        synchronized (getClassLoadingLock(name)) {
            Class<?> loadedClass = getDefinedClass(name);

            if (loadedClass == null) {
                byte[] byteCode = getByteCode(name);
                loadedClass = defineClass(name, byteCode, 0, byteCode.length);
                setDefinedClass(name, loadedClass);
            }

            resolveClass(loadedClass);
            return loadedClass;
        }
    }

    protected final Set<String> getLibraryClassNames() {
        return Collections.unmodifiableSet(libraryClassNames);
    }

    private VirtualClassURLStreamHandler getUrlStreamHandler() {
        VirtualClassURLStreamHandler handler = urlStreamHandler;
        if (handler == null) {
            handler = new VirtualClassURLStreamHandler(this);
            urlStreamHandler = handler;
        }
        return handler;
    }

    public abstract Set<String> getCompiledClasses();

    public abstract byte[] getByteCode(String className);

    public abstract Class<?> getDefinedClass(String name);

    public abstract void setDefinedClass(String name, Class<?> clazz);
}
