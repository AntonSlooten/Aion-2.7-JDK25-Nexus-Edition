package com.aionemu.commons.scripting.classlistener;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.scripting.metadata.OnClassLoad;
import com.aionemu.commons.scripting.metadata.OnClassUnload;

/**
 * Invokes static methods marked with script lifecycle annotations.
 */
public class OnClassLoadUnloadListener implements ClassListener {

    private static final Logger log = LoggerFactory.getLogger(OnClassLoadUnloadListener.class);

    @Override
    public void postLoad(Class<?>[] classes) {
        for (Class<?> type : classes) {
            doMethodInvoke(type.getDeclaredMethods(), OnClassLoad.class);
        }
    }

    @Override
    public void preUnload(Class<?>[] classes) {
        for (Class<?> type : classes) {
            doMethodInvoke(type.getDeclaredMethods(), OnClassUnload.class);
        }
    }

    protected final void doMethodInvoke(Method[] methods, Class<? extends Annotation> annotationClass) {
        for (Method method : methods) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getAnnotation(annotationClass) == null) {
                continue;
            }

            boolean restoreAccessibility = !method.canAccess(null);
            try {
                if (restoreAccessibility) {
                    method.setAccessible(true);
                }
                method.invoke(null);
            } catch (IllegalAccessException e) {
                log.error("Can't access method {} of class {}", method.getName(), method.getDeclaringClass().getName(), e);
            } catch (InvocationTargetException e) {
                log.error("Can't invoke method {} of class {}", method.getName(), method.getDeclaringClass().getName(), e);
            } finally {
                if (restoreAccessibility) {
                    method.setAccessible(false);
                }
            }
        }
    }
}
