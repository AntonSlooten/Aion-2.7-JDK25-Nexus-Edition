/*
 * This file is part of aion-emu <aion-emu.com>.
 * (License info retained)
 */
package com.aionemu.commons.scripting;

import com.aionemu.commons.scripting.url.VirtualClassURLStreamHandler;
import com.aionemu.commons.utils.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLStreamHandlerFactory;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Abstract class loader that should be extended by child classloaders.
 * Modernized for Java 25
 */
public abstract class ScriptClassLoader extends URLClassLoader {

	private static final Logger log = LoggerFactory.getLogger(ScriptClassLoader.class);

	private final VirtualClassURLStreamHandler urlStreamHandler = new VirtualClassURLStreamHandler(this);

	private Set<String> libraryClassNames = new HashSet<String>();

	private Set<File> loadedLibraries = new HashSet<File>();

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
		if(!loadedLibraries.contains(file)){
			Set<String> jarFileClasses = ClassUtils.getClassNamesFromJarFile(file);
			libraryClassNames.addAll(jarFileClasses);
			loadedLibraries.add(file);
		}
	}

	@SuppressWarnings("deprecation") // Mempertahankan URL usang demi Custom StreamHandler bawaan Aion
	@Override
	public URL getResource(String name) {
		if (!name.endsWith(".class")) {
			return super.getResource(name);
		}
		String newName = name.substring(0, name.length() - 6);
		newName = newName.replace('/', '.');
		if (getCompiledClasses().contains(newName)) {
			try {
				return new URL(null, VirtualClassURLStreamHandler.HANDLER_PROTOCOL + newName, urlStreamHandler);
			}
			catch (MalformedURLException e) {
				log.error("Can't create url for compiled class", e);
			}
		}

		return super.getResource(name);
	}

	@Override
	public Class<?> loadClass(String name) throws ClassNotFoundException {
		boolean isCompiled = getCompiledClasses().contains(name);
		if (!isCompiled) {
			return super.loadClass(name, true);
		}

		Class<?> c = getDefinedClass(name);
		if (c == null) {
			byte[] b = getByteCode(name);
			c = super.defineClass(name, b, 0, b.length);
			setDefinedClass(name, c);
		}
		return c;
	}

	protected Set<String> getLibraryClassNames(){
		return Collections.unmodifiableSet(libraryClassNames);
	}

	public abstract Set<String> getCompiledClasses();

	public abstract byte[] getByteCode(String className);

	public abstract Class<?> getDefinedClass(String name);

	public abstract void setDefinedClass(String name, Class<?> clazz) throws IllegalArgumentException;
}