/*
 * This file is part of Aion-Lightning <aion-lightning.org>.
 *
 * Aion-Lightning is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Aion-Lightning is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Aion-Lightning.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.questEngine.model;

import java.util.concurrent.ScheduledFuture;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.ThreadPoolManager;

/**
 * @author Hilgert
 */
public class QuestTimer {

	private ScheduledFuture<?> timerTask;

	private int Time = 0;

	@SuppressWarnings("unused")
	private int questId;

	private boolean isTicking = false;

	private Player player;

	/**
	 * @param questId
	 */
	public QuestTimer(int questId, int seconds, Player player) {
		this.questId = questId;
		this.Time = seconds * 1000;
		this.player = player;
	}

	/**
	 * @param seconds
	 * @param player
	 * @return
	 */
	public void Start() {
		PacketSendUtility.sendMessage(player, "Timer started");
		isTicking = true;
		// TODO Send Packet that timer start
		timerTask = ThreadPoolManager.getInstance().schedule(() -> {
			PacketSendUtility.sendMessage(player, "Timer is over");
			onEnd();
		}, Time);
	}

	public void Stop() {
		if (timerTask != null) {
			timerTask.cancel(false);
		}
		onEnd();
	}

	public void onEnd() {
		// TODO Send Packet that timer end
		isTicking = false;
	}

	/**
	 * @return true - if Timer started, and ticking.
	 * @return false - if Timer not started or stoped.
	 */
	public boolean isTicking() {
		return this.isTicking;
	}

	/**
	 * @return
	 */
	public int getTimeSeconds() {
		return this.Time / 1000;
	}
}
