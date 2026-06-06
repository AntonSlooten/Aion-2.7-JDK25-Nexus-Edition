package com.aionemu.loginserver;

import java.io.IOException;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.dao.DAOManager;
import com.aionemu.loginserver.configs.Config;
import com.aionemu.loginserver.configs.SvStatsConfig;
import com.aionemu.loginserver.dao.SvStatsDAO;
import com.aionemu.loginserver.network.gameserver.GsConnection;
import com.aionemu.loginserver.network.gameserver.serverpackets.SM_PING;

public final class PingPongThread implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PingPongThread.class);

    private final GsConnection connection;
    private final SM_PING ping = new SM_PING();

    private volatile boolean running = true;
    private byte requests;
    private int serverPID = -1;
    private boolean killProcess;

    public PingPongThread(GsConnection connection) {
        this.connection = connection;
    }

    @Override
    public void run() {
        log.info("PingPong for GameServer #{} has started.", getServerId());

        while (running) {
            sleep();

            if (!running || validateResponse()) {
                return;
            }

            sendPing();
        }
    }

    public void onResponse(int pid) {
        if (requests > 0) {
            requests--;
        }

        serverPID = pid;
    }

    public boolean validateResponse() {
        if (requests < 2) {
            return false;
        }

        running = false;

        log.info("GameServer #{} [PID={}] did not respond, closing connection.", getServerId(), serverPID);

        markServerOffline();
        connection.close(false);
        killServerProcessIfNeeded();

        return true;
    }

    public void closeMe() {
        running = false;
        markServerOffline();
    }

    private void sendPing() {
        try {
            connection.sendPacket(ping);
            requests++;

            if (SvStatsConfig.SVSTATS_ENABLE) {
                int currentId = getServerId();
                int currentPlayers = connection.getGameServerInfo().getCurrentPlayers();
                int maxPlayers = connection.getGameServerInfo().getMaxPlayers();

                DAOManager.getDAO(SvStatsDAO.class)
                        .update_SvStats_Online(currentId, 1, currentPlayers, maxPlayers);
            }
        } catch (Exception e) {
            log.error("PingPong failed for GameServer #{}.", getServerId(), e);
        }
    }

    private void markServerOffline() {
        if (!SvStatsConfig.SVSTATS_ENABLE) {
            return;
        }

        try {
            DAOManager.getDAO(SvStatsDAO.class)
                    .update_SvStats_Offline(getServerId(), 0, 0);
        } catch (Exception e) {
            log.error("Failed to mark GameServer #{} offline.", getServerId(), e);
        }
    }

    private void killServerProcessIfNeeded() {
        if (!killProcess || serverPID == -1 || !isWindows()) {
            return;
        }

        try {
            new ProcessBuilder("taskkill", "/pid", String.valueOf(serverPID), "/f")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            log.error("Failed to kill GameServer process PID {}.", serverPID, e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(Config.PINGPONG_DELAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private int getServerId() {
        return connection.getGameServerInfo().getId();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("windows");
    }
}