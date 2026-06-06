package com.aionemu.commons.database.dao;

import static com.aionemu.commons.database.DatabaseFactory.getDatabaseMajorVersion;
import static com.aionemu.commons.database.DatabaseFactory.getDatabaseMinorVersion;
import static com.aionemu.commons.database.DatabaseFactory.getDatabaseName;

import java.io.FileNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.xml.bind.JAXBException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.configs.DatabaseConfig;
import com.aionemu.commons.scripting.classlistener.AggregatedClassListener;
import com.aionemu.commons.scripting.classlistener.OnClassLoadUnloadListener;
import com.aionemu.commons.scripting.classlistener.ScheduledTaskClassListener;
import com.aionemu.commons.scripting.scriptmanager.ScriptManager;

/**
 * Resolves and manages DAO implementations for the active database engine.
 */
public final class DAOManager {

    private static final Logger log = LoggerFactory.getLogger(DAOManager.class);

    private static final Map<String, DAO> daoMap = new HashMap<>();
    private static ScriptManager scriptManager;

    private DAOManager() {
    }

    public static void init() {
        try {
            scriptManager = new ScriptManager();

            AggregatedClassListener classListener = new AggregatedClassListener();
            classListener.addClassListener(new OnClassLoadUnloadListener());
            classListener.addClassListener(new ScheduledTaskClassListener());
            classListener.addClassListener(new DAOLoader());

            scriptManager.setGlobalClassListener(classListener);
            scriptManager.load(DatabaseConfig.DATABASE_SCRIPTCONTEXT_DESCRIPTOR);

            log.info("Loaded {} DAO implementations.", daoMap.size());
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(
                    "Can't load database script context: " + DatabaseConfig.DATABASE_SCRIPTCONTEXT_DESCRIPTOR,
                    e
            );
        } catch (JAXBException e) {
            throw new IllegalStateException("Can't compile database handlers. Check your database implementations.", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Fatal error during loading or compiling database handlers.", e);
        }
    }

    public static void shutdown() {
        if (scriptManager != null) {
            scriptManager.shutdown();
            scriptManager = null;
        }

        synchronized (DAOManager.class) {
            daoMap.clear();
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends DAO> T getDAO(Class<T> daoClass) throws DAONotFoundException {
        DAO dao = daoMap.get(daoClass.getName());

        if (dao == null) {
            String message = "DAO for class " + daoClass.getSimpleName() + " is not implemented.";
            log.error(message);
            throw new DAONotFoundException(message);
        }

        return (T) dao;
    }

    public static void registerDAO(Class<? extends DAO> daoClass)
            throws DAOAlreadyRegisteredException, IllegalAccessException, InstantiationException {

        DAO dao = createDAO(daoClass);

        if (!dao.supports(getDatabaseName(), getDatabaseMajorVersion(), getDatabaseMinorVersion())) {
            return;
        }

        synchronized (DAOManager.class) {
            DAO existingDao = daoMap.get(dao.getClassName());

            if (existingDao != null) {
                String message = "DAO with className " + dao.getClassName()
                        + " is already used by " + existingDao.getClass().getName()
                        + ". Can't override with " + daoClass.getName() + ".";
                log.error(message);
                throw new DAOAlreadyRegisteredException(message);
            }

            daoMap.put(dao.getClassName(), dao);
        }

        log.debug("DAO {} was successfully registered.", dao.getClassName());
    }

    public static void unregisterDAO(Class<? extends DAO> daoClass) {
        synchronized (DAOManager.class) {
            Iterator<DAO> iterator = daoMap.values().iterator();

            while (iterator.hasNext()) {
                DAO dao = iterator.next();

                if (dao.getClass() == daoClass) {
                    iterator.remove();
                    log.debug("DAO {} was successfully unregistered.", dao.getClassName());
                    return;
                }
            }
        }
    }

    private static DAO createDAO(Class<? extends DAO> daoClass)
            throws IllegalAccessException, InstantiationException {
        try {
            return daoClass.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException | InvocationTargetException e) {
            InstantiationException exception = new InstantiationException("Can't instantiate DAO: " + daoClass.getName());
            exception.initCause(e);
            throw exception;
        }
    }
}
