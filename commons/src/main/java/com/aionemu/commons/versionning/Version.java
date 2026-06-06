package com.aionemu.commons.versionning;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads version metadata from JAR manifests.
 */
public final class Version {

    private static final Logger log = LoggerFactory.getLogger(Version.class);

    private String revision;
    private String date;
    private String branch;
    private String commitTime;

    public Version() {
    }

    public Version(Class<?> type) {
        loadInformation(type);
    }

    public void loadInformation(Class<?> type) {
        File jarName = null;
        try {
            jarName = Locator.getClassSource(type);
            try (JarFile jarFile = new JarFile(jarName)) {
                Attributes attrs = jarFile.getManifest().getMainAttributes();
                revision = getAttribute("Revision", attrs);
                date = getAttribute("Date", attrs);
                branch = getAttribute("Branch", attrs);
                commitTime = getAttribute("CommitTime", attrs);
            }
        } catch (IOException e) {
            log.error("Unable to get software information. File '{}' is not a valid jar.",
                    jarName == null ? "null" : jarName.getAbsolutePath(), e);
        }
    }

    public void transferInfo(String jarName, String type, File fileToWrite) {
        if (!fileToWrite.exists()) {
            log.error("Unable to find file: {}. Please update your {}.", fileToWrite.getName(), type);
            return;
        }

        try (JarFile jarFile = new JarFile("./" + jarName);
             OutputStream outputStream = new FileOutputStream(fileToWrite)) {
            Manifest manifest = jarFile.getManifest();
            manifest.write(outputStream);
        } catch (IOException e) {
            log.error("Failed to transfer manifest information.", e);
        }
    }

    public String getRevision() {
        return revision;
    }

    public String getDate() {
        return date;
    }

    public String getBranch() {
        return branch;
    }

    public String getCommitTime() {
        return commitTime;
    }

    private static String getAttribute(String attribute, Attributes attrs) {
        String value = attrs.getValue(attribute);
        return value != null ? value : "Unknown " + attribute;
    }
}
