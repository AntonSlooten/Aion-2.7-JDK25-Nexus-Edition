/*
 * This file is part of InPanic Core <Ver:3.1>.
 * (License info retained)
 */
package com.aionemu.commons.versionning;

import java.io.File;
import java.io.FilenameFilter;
import java.net.MalformedURLException;
import java.net.URI; // Tambahkan ini
import java.net.URISyntaxException; // Tambahkan ini
import java.net.URL;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.Locale;

/**
 * The Locator is a utility class which is used to find certain items in the environment.
 * Modernized for Java 25 (URI toURL)
 */
public final class Locator {

	private Locator() {
	}

	public static File getClassSource(Class<?> c) {
		String classResource = c.getName().replace('.', '/') + ".class";
		return getResourceSource(c.getClassLoader(), classResource);
	}

	public static File getResourceSource(ClassLoader c, String resource) {
		if (c == null) {
			c = Locator.class.getClassLoader();
		}
		URL url = null;
		if (c == null) {
			url = ClassLoader.getSystemResource(resource);
		}
		else {
			url = c.getResource(resource);
		}
		if (url != null) {
			String u = url.toString();
			if (u.startsWith("jar:file:")) {
				int pling = u.indexOf("!");
				String jarName = u.substring(4, pling);
				return new File(fromURI(jarName));
			}
			else if (u.startsWith("file:")) {
				int tail = u.indexOf(resource);
				String dirName = u.substring(0, tail);
				return new File(fromURI(dirName));
			}
		}
		return null;
	}

	public static String fromURI(String uri) {
		URL url = null;
		try {
			// Perbaikan dari new URL(uri) ke URI.create(uri).toURL()
			url = new URI(uri).toURL();
		}
		catch (MalformedURLException | URISyntaxException | IllegalArgumentException e) {
			// Ignore exception
		}
		if (url == null || !("file".equals(url.getProtocol()))) {
			throw new IllegalArgumentException("Can only handle valid file: URIs");
		}
		StringBuffer buf = new StringBuffer(url.getHost());
		if (buf.length() > 0) {
			buf.insert(0, File.separatorChar).insert(0, File.separatorChar);
		}
		String file = url.getFile();
		int queryPos = file.indexOf('?');
		buf.append((queryPos < 0) ? file : file.substring(0, queryPos));

		uri = buf.toString().replace('/', File.separatorChar);

		if (File.pathSeparatorChar == ';' && uri.startsWith("\\") && uri.length() > 2 && Character.isLetter(uri.charAt(1))
			&& uri.lastIndexOf(':') > -1) {
			uri = uri.substring(1);
		}
		String path = decodeUri(uri);
		return path;
	}

	private static String decodeUri(String uri) {
		if (uri.indexOf('%') == -1) {
			return uri;
		}
		StringBuffer sb = new StringBuffer();
		CharacterIterator iter = new StringCharacterIterator(uri);
		for (char c = iter.first(); c != CharacterIterator.DONE; c = iter.next()) {
			if (c == '%') {
				char c1 = iter.next();
				if (c1 != CharacterIterator.DONE) {
					int i1 = Character.digit(c1, 16);
					char c2 = iter.next();
					if (c2 != CharacterIterator.DONE) {
						int i2 = Character.digit(c2, 16);
						sb.append((char) ((i1 << 4) + i2));
					}
				}
			}
			else {
				sb.append(c);
			}
		}
		String path = sb.toString();
		return path;
	}

	public static File getToolsJar() {
		boolean toolsJarAvailable = false;
		try {
			Class.forName("com.sun.tools.javac.Main");
			toolsJarAvailable = true;
		}
		catch (Exception e) {
			try {
				Class.forName("sun.tools.javac.Main");
				toolsJarAvailable = true;
			}
			catch (Exception e2) {
				// ignore
			}
		}
		if (toolsJarAvailable) {
			return null;
		}
		String javaHome = System.getProperty("java.home");
		if (javaHome.toLowerCase(Locale.US).endsWith("jre")) {
			javaHome = javaHome.substring(0, javaHome.length() - 4);
		}
		File toolsJar = new File(javaHome + "/lib/tools.jar");
		if (!toolsJar.exists()) {
			System.out.println("Unable to locate tools.jar. " + "Expected to find it in " + toolsJar.getPath());
			return null;
		}
		return toolsJar;
	}

	public static URL[] getLocationURLs(File location) throws MalformedURLException {
		return getLocationURLs(location, new String[] { ".jar" });
	}

	public static URL[] getLocationURLs(File location, final String[] extensions) throws MalformedURLException {

		URL[] urls = new URL[0];

		if (!location.exists()) {
			return urls;
		}
		if (!location.isDirectory()) {
			urls = new URL[1];
			String path = location.getPath();
			for (int i = 0; i < extensions.length; ++i) {
				if (path.toLowerCase().endsWith(extensions[i])) {
					urls[0] = location.toURI().toURL();
					break;
				}
			}
			return urls;
		}
		File[] matches = location.listFiles(new FilenameFilter() {

			@Override
			public boolean accept(File dir, String name) {
				for (int i = 0; i < extensions.length; ++i) {
					if (name.toLowerCase().endsWith(extensions[i])) {
						return true;
					}
				}
				return false;
			}
		});
		urls = new URL[matches.length];
		for (int i = 0; i < matches.length; ++i) {
			urls[i] = matches[i].toURI().toURL();
		}
		return urls;
	}
}