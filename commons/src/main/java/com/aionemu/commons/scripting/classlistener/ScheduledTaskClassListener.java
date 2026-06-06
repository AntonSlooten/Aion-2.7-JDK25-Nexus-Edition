package com.aionemu.commons.scripting.classlistener;

import java.util.Map;

import org.quartz.JobDetail;

import com.aionemu.commons.scripting.metadata.Scheduled;
import com.aionemu.commons.services.CronService;
import com.aionemu.commons.utils.ClassUtils;

/**
 * Registers and unregisters script classes annotated with {@link Scheduled}.
 */
public class ScheduledTaskClassListener implements ClassListener {

    @Override
    @SuppressWarnings("unchecked")
    public void postLoad(Class<?>[] classes) {
        for (Class<?> clazz : classes) {
            if (isValidClass(clazz)) {
                scheduleClass((Class<? extends Runnable>) clazz);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void preUnload(Class<?>[] classes) {
        for (Class<?> clazz : classes) {
            if (isValidClass(clazz)) {
                unScheduleClass((Class<? extends Runnable>) clazz);
            }
        }
    }

    public boolean isValidClass(Class<?> clazz) {
        return ClassUtils.isSubclass(clazz, Runnable.class)
                && !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())
                && !java.lang.reflect.Modifier.isInterface(clazz.getModifiers())
                && java.lang.reflect.Modifier.isPublic(clazz.getModifiers())
                && clazz.isAnnotationPresent(Scheduled.class)
                && !clazz.getAnnotation(Scheduled.class).disabled()
                && clazz.getAnnotation(Scheduled.class).value().length > 0;
    }

    protected void scheduleClass(Class<? extends Runnable> clazz) {
        Scheduled metadata = clazz.getAnnotation(Scheduled.class);

        try {
            if (metadata.instancePerCronExpression()) {
                for (String cronExpression : metadata.value()) {
                    getCronService().schedule(createRunnable(clazz), cronExpression, metadata.longRunningTask());
                }
            } else {
                Runnable runnable = createRunnable(clazz);
                for (String cronExpression : metadata.value()) {
                    getCronService().schedule(runnable, cronExpression, metadata.longRunningTask());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to schedule runnable " + clazz.getName(), e);
        }
    }

    protected void unScheduleClass(Class<? extends Runnable> clazz) {
        Map<Runnable, JobDetail> map = getCronService().getRunnables();
        for (Map.Entry<Runnable, JobDetail> entry : map.entrySet()) {
            if (entry.getKey().getClass() == clazz) {
                getCronService().cancel(entry.getValue());
            }
        }
    }

    protected CronService getCronService() {
        if (CronService.getInstance() == null) {
            throw new IllegalStateException("CronService is not initialized");
        }
        return CronService.getInstance();
    }

    private static Runnable createRunnable(Class<? extends Runnable> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create runnable " + clazz.getName(), e);
        }
    }
}
