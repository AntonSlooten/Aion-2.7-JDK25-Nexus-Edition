/*
 * This file is part of aion-emu <aion-emu.com>.
 * (License info retained)
 */
package com.aionemu.commons.scripting.impl.javacompiler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset; // TAMBAHAN IMPORT

import javax.tools.SimpleJavaFileObject;

import org.apache.commons.io.FileUtils;

public class JavaSourceFromFile extends SimpleJavaFileObject {

	public JavaSourceFromFile(File file, Kind kind) {
		super(file.toURI(), kind);
	}

	@Override
	public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
		// PERBAIKAN: Menambahkan Charset
		return FileUtils.readFileToString(new File(this.toUri()), Charset.defaultCharset());
	}
}