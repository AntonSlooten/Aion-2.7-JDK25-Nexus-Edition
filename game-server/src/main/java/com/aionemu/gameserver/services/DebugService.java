/*
 * This file is part of aion-unique <aion-unique.org>.
 *
 *  aion-unique is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  aion-unique is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with aion-unique.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.services;

import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.DebugConfig;
import com.aionemu.gameserver.model.gameobjects.player.BotPlayer;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team2.group.PlayerGroup;
import com.aionemu.gameserver.network.aion.AionConnection;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;

/**
 * @author ATracer 
 */
public class DebugService {

	private static final Logger log = LoggerFactory.getLogger(DebugService.class);

	private static final int ANALYZE_PLAYERS_INTERVAL = 30 * 60 * 1000;

	/**
	 * TEMPORARY diagnostic logging (remove once the client-crash investigation is done) - a snapshot of
	 * every Player currently in World, bots included, every 10s. Existing analyzeWorldPlayers() above
	 * only runs every 30 minutes and skips bots entirely (bails out the moment it sees a null client
	 * connection, which every bot has). This is dense enough to bracket a crash that lands anywhere from
	 * ~15s to a few minutes after some triggering state change, without needing to catch it live in a
	 * tail -f. Requested live: "Can you put some periodic logging in to determine what the state of all
	 * active players are on the server? Potentially bots included... shed some light in the timing and
	 * show a correlation to a state change."
	 */
	private static final int SNAPSHOT_INTERVAL = 10 * 1000;

	/**
	 * Diagnostic for intermittent "nothing acknowledges you - shopkeepers don't respond to talk, though
	 * you can still move freely - then it all comes back after a while" reports, suspected tied to the
	 * JDK 5->21+ migration. All client packet handling funnels through AionConnection's PacketProcessor,
	 * fixed at exactly 4 worker threads (network.properties: packet.processor.threads.min == .max, which
	 * also means the pool's own auto-scaling/overload-alarm logic never runs at all - see
	 * PacketProcessor.getThreadCount()'s doc). A queue that's growing, or a thread count pinned below
	 * what's configured, over the window the user reports the freeze is the smoking gun to look for.
	 * PacketProcessor itself separately logs any individual packet whose execution takes >2s
	 * ("[packetlag] ..."), which would name the specific culprit packet type if one is chronically slow.
	 */
	private static final int PACKET_HEALTH_INTERVAL = 10 * 1000;

	public static final DebugService getInstance() {
		return SingletonHolder.instance;
	}

	private DebugService() {
		ThreadPoolManager.getInstance().scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				analyzeWorldPlayers();
			}

		}, ANALYZE_PLAYERS_INTERVAL, ANALYZE_PLAYERS_INTERVAL);
		log.info("DebugService started. Analyze iterval: " + ANALYZE_PLAYERS_INTERVAL);

		ThreadPoolManager.getInstance().scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				snapshotWorldPlayers();
			}

		}, SNAPSHOT_INTERVAL, SNAPSHOT_INTERVAL);

		ThreadPoolManager.getInstance().scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				snapshotPacketProcessorHealth();
			}

		}, PACKET_HEALTH_INTERVAL, PACKET_HEALTH_INTERVAL);
	}

	private void snapshotPacketProcessorHealth() {
		if (!DebugConfig.NETWORK_LOGGING)
			return;
		int queueSize = AionConnection.getPacketQueueSize();
		int threadCount = AionConnection.getPacketProcessorThreadCount();
		log.info("[PACKETHEALTH] queue=" + queueSize + " threads=" + threadCount);
	}

	private void snapshotWorldPlayers() {
		if (!DebugConfig.NETWORK_LOGGING)
			return;
		Iterator<Player> playersIterator = World.getInstance().getPlayersIterator();
		StringBuilder sb = new StringBuilder("[SNAPSHOT]");
		int count = 0;
		while (playersIterator.hasNext()) {
			Player player = playersIterator.next();
			count++;
			sb.append(" | ").append(player.getName()).append(" objId=").append(player.getObjectId());
			if (player instanceof BotPlayer) {
				BotPlayer bot = (BotPlayer) player;
				Player host = World.getInstance().findPlayer(bot.getHostObjectId());
				sb.append(" bot host=").append(host != null ? host.getName() : "MISSING(" + bot.getHostObjectId() + ")");
			}
			else {
				AionConnection connection = player.getClientConnection();
				sb.append(" real conn=").append(connection != null);
			}
			sb.append(" spawned=").append(player.isSpawned());
			sb.append(" online=").append(player.isOnline());
			sb.append(" world=").append(player.getWorldId());
			PlayerGroup group = player.getPlayerGroup2();
			sb.append(" group=").append(group != null ? group.getTeamId() + "(size=" + group.size() + ")" : "none");
		}
		log.info(sb.append(" | total=").append(count).toString());
	}

	private void analyzeWorldPlayers() {
		log.info("Starting analysis of world players at " + System.currentTimeMillis());

		Iterator<Player> playersIterator = World.getInstance().getPlayersIterator();
		while (playersIterator.hasNext()) {
			Player player = playersIterator.next();

			// Companion bots (BotPlayer) are a connectionless Player subclass by design - they never
			// have a client connection, so the check below would otherwise warn on every bot, every
			// cycle, forever. Confirmed live: "[DEBUG SERVICE] Player without connection: detected:
			// ObjId 78614, Name Summon, Spawned true" - "Summon" here is a companion bot's character
			// name, not an actual pet/summon object.
			if (player instanceof BotPlayer)
				continue;

			/**
			 * Check connection
			 */
			AionConnection connection = player.getClientConnection();
			if (connection == null) {
				log.warn(String.format("[DEBUG SERVICE] Player without connection: "
					+ "detected: ObjId %d, Name %s, Spawned %s", player.getObjectId(), player.getName(), player.isSpawned()));
				continue;
			}

			/**
			 * Check CM_PING packet
			 */
			long lastPingTimeMS = connection.getLastPingTimeMS();
			long pingInterval = System.currentTimeMillis() - lastPingTimeMS;
			if (lastPingTimeMS > 0 && pingInterval > 300000) {
				log.warn(String.format("[DEBUG SERVICE] Player with large ping interval: "
					+ "ObjId %d, Name %s, Spawned %s, PingMS %d", player.getObjectId(), player.getName(), player.isSpawned(),
					pingInterval));
			}
		}
	}

	private static class SingletonHolder {

		protected static final DebugService instance = new DebugService();
	}
}
