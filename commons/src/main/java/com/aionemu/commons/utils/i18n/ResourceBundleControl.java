package com.aionemu.commons.utils.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * Resource bundle control that reads properties with a configurable charset.
 */
public class ResourceBundleControl extends ResourceBundle.Control {

    private String encoding = "UTF-8";

    public ResourceBundleControl() {
    }

    public ResourceBundleControl(String encoding) {
        this.encoding = encoding;
    }

    @Override
    public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload)
            throws IllegalAccessException, InstantiationException, IOException {
        String bundleName = toBundleName(baseName, locale);

        if ("java.class".equals(format)) {
            try {
                @SuppressWarnings("unchecked")
                Class<? extends ResourceBundle> bundleClass =
                        (Class<? extends ResourceBundle>) loader.loadClass(bundleName);

                if (!ResourceBundle.class.isAssignableFrom(bundleClass)) {
                    throw new ClassCastException(bundleClass.getName() + " cannot be cast to ResourceBundle");
                }

                return bundleClass.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException ignored) {
                return null;
            } catch (ReflectiveOperationException e) {
                InstantiationException exception = new InstantiationException(
                        "Failed to instantiate resource bundle: " + bundleName
                );
                exception.initCause(e);
                throw exception;
            }
        }

        if ("java.properties".equals(format)) {
            String resourceName = toResourceName(bundleName, "properties");
            try (InputStream stream = openResourceStream(loader, resourceName, reload)) {
                if (stream == null) {
                    return null;
                }
                return new PropertyResourceBundle(new InputStreamReader(stream, Charset.forName(encoding)));
            }
        }

        throw new IllegalArgumentException("unknown format: " + format);
    }

    private static InputStream openResourceStream(ClassLoader classLoader, String resourceName, boolean reload)
            throws IOException {
        if (!reload) {
            return classLoader.getResourceAsStream(resourceName);
        }

        URL url = classLoader.getResource(resourceName);
        if (url == null) {
            return null;
        }

        URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }
}
