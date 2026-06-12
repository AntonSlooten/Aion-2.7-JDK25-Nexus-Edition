/*
 * This file is part of aion-lightning <aion-lightning.com>.
 *
 *  aion-lightning is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  aion-lightning is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with aion-lightning.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.spawnengine;

import com.aionemu.gameserver.model.TaskId;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.templates.spawns.SpawnGroup2;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;
import com.aionemu.gameserver.model.templates.spawns.TemporarySpawn;
import javolution.util.FastList;

/**
 * @author xTz
 */
public class TemporarySpawnEngine {

	private static final FastList<SpawnGroup2> temporarySpawns = new FastList<SpawnGroup2>();

	public static void spawnAll() {
		spwan(true);
	}

	public static void onHourChange() {
		despawn();
		spwan(false);
	}

	private static void despawn() {
		for (SpawnGroup2 spawn : temporarySpawns) {
			for (SpawnTemplate template : spawn.getSpawnTemplates()) {
				if (template.getTemporarySpawn().canDespawn()) {
					VisibleObject object = template.getVisibleObject();
					if (object == null) {
						continue;
					}
					if (object instanceof Npc) {
						Npc npc = (Npc) object;
						if (!npc.getLifeStats().isAlreadyDead() && template.hasPool()) {
							template.setUse(false);
						}
						npc.getController().cancelTask(TaskId.RESPAWN);
					}
					if (object.isSpawned()) {
						object.getController().onDelete();
					}
				}
			}
		}
	}

	private static void spwan(boolean startCheck) {
		for (SpawnGroup2 spawn : temporarySpawns) {
			if (spawn.hasPool()) {
				TemporarySpawn temporarySpawn = spawn.geTemporarySpawn();
				if (temporarySpawn.canSpawn() || (startCheck && spawn.getRespawnTime() != 0
						&& temporarySpawn.isInSpawnTime())) {
					for (int pool = 0; pool < spawn.getPool(); pool++) {
						SpawnTemplate template = spawn.getRndTemplate();
						SpawnEngine.spawnObject(template, 1);
					}
				}
			}
			else {
				for (SpawnTemplate template : spawn.getSpawnTemplates()) {
					TemporarySpawn temporarySpawn = template.getTemporarySpawn();
					if (temporarySpawn.canSpawn() || (startCheck && !template.isNoRespawn()
							&& temporarySpawn.isInSpawnTime())) {
						SpawnEngine.spawnObject(template, 1);
					}
				}
			}
		}
	}

	/**
	 * @param spawnTemplate
	 */
	public static void addSpawnGroup(SpawnGroup2 spawn) {
		temporarySpawns.add(spawn);
	}
} 