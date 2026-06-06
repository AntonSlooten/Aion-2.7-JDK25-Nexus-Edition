package com.aionemu.loginserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.commons.database.dao.DAOManager;
import com.aionemu.commons.services.CronService;
import com.aionemu.commons.utils.ExitCode;
import com.aionemu.loginserver.configs.Config;
import com.aionemu.loginserver.controller.BannedIpController;
import com.aionemu.loginserver.controller.PremiumController;
import com.aionemu.loginserver.dao.BannedMacDAO;
import com.aionemu.loginserver.network.NetConnector;
import com.aionemu.loginserver.network.ncrypt.KeyGen;
import com.aionemu.loginserver.service.PlayerTransferService;
import com.aionemu.loginserver.taskmanager.TaskFromDBManager;
import com.aionemu.loginserver.utils.DeadLockDetector;
import com.aionemu.loginserver.utils.ThreadPoolManager;
import com.aionemu.loginserver.utils.cron.ThreadPoolManagerRunnableRunner;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;

public final class LoginServer {

    private static final Logger log = LoggerFactory.getLogger(LoginServer.class);

    private static final Path LOG_DIR = Path.of("log");
    private static final Path LOG_BACKUP_DIR = LOG_DIR.resolve("backup");
    private static final Path LOGBACK_CONFIG = Path.of("config", "slf4j-logback.xml");

    private LoginServer() {
    }

    public static void main(String[] args) {
        long startedAt = System.currentTimeMillis();

        try {
            initializeLogger();

            printHeader();

            CronService.initSingleton(ThreadPoolManagerRunnableRunner.class);

            new ServerCommandProcessor().start();

            log.info("Loading configuration...");
            Config.load();

            log.info("Initializing database...");
            DatabaseFactory.init();

            log.info("Loading DAO implementations...");
            DAOManager.init();

            log.info("Starting deadlock detector...");
            new DeadLockDetector(60, DeadLockDetector.RESTART).start();

            log.info("Initializing thread pool...");
            ThreadPoolManager.getInstance();

            log.info("Initializing key generator...");
            KeyGen.init();

            log.info("Loading game server table...");
            GameServerTable.load();

            log.info("Starting ban controllers...");
            BannedIpController.start();
            DAOManager.getDAO(BannedMacDAO.class).cleanExpiredBans();

            log.info("Connecting network services...");
            NetConnector.getInstance().connect();

            log.info("Initializing player transfer service...");
            PlayerTransferService.getInstance();

            log.info("Initializing task manager...");
            TaskFromDBManager.getInstance();

            Runtime.getRuntime().addShutdownHook(Shutdown.getInstance());

            PremiumController.getController();

            printSystemInfo();

            log.info("============================================================");
            log.info("NEXUS CONNECT LOGIN SERVER STARTED SUCCESSFULLY");
            log.info("Boot Time: {} ms", System.currentTimeMillis() - startedAt);
            log.info("============================================================");

        } catch (Throwable e) {
            log.error("Failed to start Nexus Connect Login Server.", e);
            System.exit(ExitCode.CODE_ERROR);
        }
    }

    private static void initializeLogger() {
        backupOldLogs();
        configureLogback();
    }

    private static void backupOldLogs() {
        try {
            Files.createDirectories(LOG_BACKUP_DIR);

            try (DirectoryStream<Path> logs = Files.newDirectoryStream(LOG_DIR, "*.log")) {
                Path backupFile = LOG_BACKUP_DIR.resolve(
                        new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date()) + ".zip"
                );

                boolean hasLogs = false;

                try (OutputStream fileOut = Files.newOutputStream(backupFile);
                     ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {

                    zipOut.setMethod(ZipOutputStream.DEFLATED);
                    zipOut.setLevel(Deflater.BEST_COMPRESSION);

                    byte[] buffer = new byte[8192];

                    for (Path logFile : logs) {
                        if (!Files.isRegularFile(logFile)) {
                            continue;
                        }

                        hasLogs = true;
                        zipOut.putNextEntry(new ZipEntry(logFile.getFileName().toString()));

                        try (InputStream input = Files.newInputStream(logFile)) {
                            int length;
                            while ((length = input.read(buffer)) > 0) {
                                zipOut.write(buffer, 0, length);
                            }
                        }

                        zipOut.closeEntry();
                    }
                }

                if (hasLogs) {
                    try (DirectoryStream<Path> oldLogs = Files.newDirectoryStream(LOG_DIR, "*.log")) {
                        for (Path oldLog : oldLogs) {
                            Files.deleteIfExists(oldLog);
                        }
                    }
                } else {
                    Files.deleteIfExists(backupFile);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to backup old log files: " + e.getMessage());
        }
    }

    private static void configureLogback() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);

            context.reset();
            configurator.doConfigure(LOGBACK_CONFIG.toString());
        } catch (JoranException e) {
            throw new IllegalStateException("Failed to configure loggers.", e);
        }
    }

    private static void printHeader() {
        log.info("============================================================");
        log.info("                 NEXUS CONNECT LOGIN SERVER");
        log.info("                   Aion 2.7 Java 25 Edition");
        log.info("============================================================");
    }

    private static void printSystemInfo() {
        log.info("System Information");
        log.info("------------------------------------------------------------");
        log.info("Java Version : {}", System.getProperty("java.version"));
        log.info("Java Vendor  : {}", System.getProperty("java.vendor"));
        log.info("OS Name      : {}", System.getProperty("os.name"));
        log.info("OS Version   : {}", System.getProperty("os.version"));
        log.info("OS Arch      : {}", System.getProperty("os.arch"));
        log.info("Processors   : {}", Runtime.getRuntime().availableProcessors());
        log.info("Max Memory   : {} MB", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        log.info("Total Memory : {} MB", Runtime.getRuntime().totalMemory() / 1024 / 1024);
        log.info("Free Memory  : {} MB", Runtime.getRuntime().freeMemory() / 1024 / 1024);
        log.info("------------------------------------------------------------");
    }
}