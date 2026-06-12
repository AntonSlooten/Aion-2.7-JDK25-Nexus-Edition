/*
 * This file is part of aion-emu <aion-emu.com>.
 * (License info retained)
 */
package com.aionemu.commons.configuration;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;
import java.lang.reflect.InvocationTargetException;

import com.aionemu.commons.configuration.transformers.*;
import com.aionemu.commons.utils.ClassUtils;

public class PropertyTransformerFactory {

	@SuppressWarnings("rawtypes")
	public static PropertyTransformer newTransformer(Class clazzToTransform, Class<? extends PropertyTransformer> tc)
		throws TransformationException {

		if (tc == PropertyTransformer.class) {
			tc = null;
		}

		if (tc != null) {
			try {
				// PERBAIKAN: Java 9+ newInstance
				return tc.getDeclaredConstructor().newInstance();
			}
			catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
				throw new TransformationException("Can't instantiate property transfromer", e);
			}
		}
		if (clazzToTransform == Boolean.class || clazzToTransform == Boolean.TYPE) {
			return BooleanTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == Byte.class || clazzToTransform == Byte.TYPE) {
			return ByteTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == Character.class || clazzToTransform == Character.TYPE) {
			return CharTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == Double.class || clazzToTransform == Double.TYPE) {
			return DoubleTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == Float.class || clazzToTransform == Float.TYPE) {
			return FloatTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == Integer.class || clazzToTransform == Integer.TYPE) {
			return IntegerTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == Long.class || clazzToTransform == Long.TYPE) {
			return LongTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == Short.class || clazzToTransform == Short.TYPE) {
			return ShortTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == String.class) {
			return StringTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform.isEnum()) {
			return EnumTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == File.class) {
			return FileTransformer.SHARED_INSTANCE;
		}
		else if (ClassUtils.isSubclass(clazzToTransform, InetSocketAddress.class)) {
			return InetSocketAddressTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == Pattern.class) {
			return PatternTransformer.SHARED_INSTANCE;
		}
		else if (clazzToTransform == Class.class) {
			return ClassTransformer.SHARED_INSTANCE;
		}
		else {
			throw new TransformationException("Transformer not found for class " + clazzToTransform.getName());
		}
	}
}