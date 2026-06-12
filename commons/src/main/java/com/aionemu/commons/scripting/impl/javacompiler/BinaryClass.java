/*
 * This file is part of aion-emu <aion-emu.com>.
 *
 * aion-emu is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aion-emu is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with aion-emu.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.commons.scripting.impl.javacompiler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;

import javax.tools.SimpleJavaFileObject;

/**
 * Modernized for Java 25 compatibility.
 * Replaced the deprecated/removed com.sun.tools.javac.file.BaseFileObject 
 * with the standard javax.tools.SimpleJavaFileObject.
 * * @author SoulKeeper
 * @author Nexus Connect (Java Modernization)
 */
public class BinaryClass extends SimpleJavaFileObject {

	private final String name;
	private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
	private Class<?> definedClass;

	protected BinaryClass(String name) {
		super(URI.create("string:///" + name.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
		this.name = name;
	}

	@Deprecated
	@Override
	public String getName() {
		return name + ".class";
	}

	@Override
	public InputStream openInputStream() throws IOException {
		return new ByteArrayInputStream(baos.toByteArray());
	}

	@Override
	public OutputStream openOutputStream() throws IOException {
		return baos;
	}

	@Override
	public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Writer openWriter() throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getLastModified() {
		return 0;
	}

	@Override
	public boolean delete() {
		return false;
	}

	@Override
	public boolean isNameCompatible(String simpleName, Kind kind) {
		return Kind.CLASS.equals(kind);
	}

	public byte[] getBytes() {
		return baos.toByteArray();
	}

	public Class<?> getDefinedClass() {
		return definedClass;
	}

	public void setDefinedClass(Class<?> definedClass) {
		this.definedClass = definedClass;
	}

	@Override
	public Kind getKind() {
		return Kind.CLASS;
	}

	/**
	 * Custom method retained for ClassFileManager compatibility.
	 * Removed @Override because SimpleJavaFileObject doesn't define it.
	 */
	public String inferBinaryName(Iterable<? extends File> path) {
		return name;
	}
}