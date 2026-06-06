package com.aionemu.commons.configuration;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;

import com.aionemu.commons.configuration.transformers.BooleanTransformer;
import com.aionemu.commons.configuration.transformers.ByteTransformer;
import com.aionemu.commons.configuration.transformers.CharTransformer;
import com.aionemu.commons.configuration.transformers.ClassTransformer;
import com.aionemu.commons.configuration.transformers.DoubleTransformer;
import com.aionemu.commons.configuration.transformers.EnumTransformer;
import com.aionemu.commons.configuration.transformers.FileTransformer;
import com.aionemu.commons.configuration.transformers.FloatTransformer;
import com.aionemu.commons.configuration.transformers.InetSocketAddressTransformer;
import com.aionemu.commons.configuration.transformers.IntegerTransformer;
import com.aionemu.commons.configuration.transformers.LongTransformer;
import com.aionemu.commons.configuration.transformers.PatternTransformer;
import com.aionemu.commons.configuration.transformers.ShortTransformer;
import com.aionemu.commons.configuration.transformers.StringTransformer;
import com.aionemu.commons.utils.ClassUtils;

/**
 * Factory for property transformers used by {@link ConfigurableProcessor}.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public final class PropertyTransformerFactory {

    private PropertyTransformerFactory() {
    }

    public static PropertyTransformer<?> newTransformer(
            Class<?> type,
            Class<? extends PropertyTransformer> transformerClass
    ) throws TransformationException {

        if (transformerClass != null && transformerClass != PropertyTransformer.class) {
            return createCustomTransformer(transformerClass);
        }

        if (type == Boolean.class || type == Boolean.TYPE) {
            return BooleanTransformer.SHARED_INSTANCE;
        }
        if (type == Byte.class || type == Byte.TYPE) {
            return ByteTransformer.SHARED_INSTANCE;
        }
        if (type == Character.class || type == Character.TYPE) {
            return CharTransformer.SHARED_INSTANCE;
        }
        if (type == Double.class || type == Double.TYPE) {
            return DoubleTransformer.SHARED_INSTANCE;
        }
        if (type == Float.class || type == Float.TYPE) {
            return FloatTransformer.SHARED_INSTANCE;
        }
        if (type == Integer.class || type == Integer.TYPE) {
            return IntegerTransformer.SHARED_INSTANCE;
        }
        if (type == Long.class || type == Long.TYPE) {
            return LongTransformer.SHARED_INSTANCE;
        }
        if (type == Short.class || type == Short.TYPE) {
            return ShortTransformer.SHARED_INSTANCE;
        }
        if (type == String.class) {
            return StringTransformer.SHARED_INSTANCE;
        }
        if (type.isEnum()) {
            return EnumTransformer.SHARED_INSTANCE;
        }
        if (type == File.class) {
            return FileTransformer.SHARED_INSTANCE;
        }
        if (ClassUtils.isSubclass(type, InetSocketAddress.class)) {
            return InetSocketAddressTransformer.SHARED_INSTANCE;
        }
        if (type == Pattern.class) {
            return PatternTransformer.SHARED_INSTANCE;
        }
        if (type == Class.class) {
            return ClassTransformer.SHARED_INSTANCE;
        }

        throw new TransformationException("Transformer not found for class " + type.getName());
    }

    private static PropertyTransformer<?> createCustomTransformer(
            Class<? extends PropertyTransformer> transformerClass
    ) throws TransformationException {
        try {
            return (PropertyTransformer<?>) transformerClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException
                 | IllegalAccessException
                 | InvocationTargetException
                 | NoSuchMethodException e) {
            throw new TransformationException(
                    "Can't instantiate property transformer: " + transformerClass.getName(),
                    e
            );
        }
    }
}
