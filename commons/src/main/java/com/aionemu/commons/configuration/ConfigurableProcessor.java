package com.aionemu.commons.configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes fields annotated with {@link Property} and injects values from property files.
 */
public final class ConfigurableProcessor {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableProcessor.class);

    private ConfigurableProcessor() {
    }

    public static void process(Object target, Properties... properties) {
        Objects.requireNonNull(target, "target");

        Class<?> type;
        Object instance;

        if (target instanceof Class<?> clazz) {
            type = clazz;
            instance = null;
        } else {
            type = target.getClass();
            instance = target;
        }

        process(type, instance, properties == null ? new Properties[0] : properties);
    }

    private static void process(Class<?> type, Object instance, Properties[] properties) {
        processFields(type, instance, properties);

        if (instance == null) {
            for (Class<?> interfaceType : type.getInterfaces()) {
                process(interfaceType, null, properties);
            }
        }

        Class<?> superClass = type.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            process(superClass, instance, properties);
        }
    }

    private static void processFields(Class<?> type, Object instance, Properties[] properties) {
        for (Field field : type.getDeclaredFields()) {
            boolean staticField = Modifier.isStatic(field.getModifiers());

            if (staticField && instance != null) {
                continue;
            }
            if (!staticField && instance == null) {
                continue;
            }
            if (!field.isAnnotationPresent(Property.class)) {
                continue;
            }
            if (Modifier.isFinal(field.getModifiers())) {
                throw new IllegalStateException(
                        "Cannot process final property field " + field.getName() + " in class " + type.getName()
                );
            }

            processField(field, instance, properties);
        }
    }

    private static void processField(Field field, Object instance, Properties[] properties) {
        Object accessTarget = Modifier.isStatic(field.getModifiers()) ? null : instance;
        boolean restoreAccessibility = !field.canAccess(accessTarget);

        try {
            if (restoreAccessibility) {
                field.setAccessible(true);
            }

            Property property = field.getAnnotation(Property.class);
            if (!Property.DEFAULT_VALUE.equals(property.defaultValue()) || isKeyPresent(property.key(), properties)) {
                field.set(instance, getFieldValue(field, properties));
            } else {
                log.debug("Field {} of class {} was not modified", field.getName(), field.getDeclaringClass().getName());
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot transform field " + field.getName() + " of class " + field.getDeclaringClass().getName(),
                    e
            );
        } finally {
            if (restoreAccessibility) {
                field.setAccessible(false);
            }
        }
    }

    private static Object getFieldValue(Field field, Properties[] properties) throws TransformationException {
        Property property = field.getAnnotation(Property.class);
        String key = property.key();
        String value = null;

        if (key.isBlank()) {
            log.warn("Property {} of class {} has an empty key", field.getName(), field.getDeclaringClass().getName());
        } else {
            value = findPropertyByKey(key, properties);
        }

        if (value == null || value.isBlank()) {
            value = property.defaultValue();
            log.debug("Using default value for field {} of class {}", field.getName(), field.getDeclaringClass().getName());
        }

        PropertyTransformer<?> transformer = PropertyTransformerFactory.newTransformer(
                field.getType(), property.propertyTransformer());
        return transformer.transform(value, field);
    }

    private static String findPropertyByKey(String key, Properties[] properties) {
        for (Properties propertySet : properties) {
            if (propertySet != null && propertySet.containsKey(key)) {
                return propertySet.getProperty(key);
            }
        }
        return null;
    }

    private static boolean isKeyPresent(String key, Properties[] properties) {
        return findPropertyByKey(key, properties) != null;
    }
}
