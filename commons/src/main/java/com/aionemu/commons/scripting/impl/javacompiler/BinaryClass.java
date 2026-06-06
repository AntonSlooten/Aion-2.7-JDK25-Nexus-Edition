package com.aionemu.commons.scripting.impl.javacompiler;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

public class BinaryClass extends SimpleJavaFileObject {

    private final String name;
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    private Class<?> definedClass;

    protected BinaryClass(String name) {
        super(URI.create("bytes:///" + name.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        this.name = name;
    }

    @Override
    public String getName() {
        return name + Kind.CLASS.extension;
    }

    @Override
    public InputStream openInputStream() throws IOException {
        return new ByteArrayInputStream(baos.toByteArray());
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        return baos;
    }

    public String inferBinaryName() {
        return name;
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
}