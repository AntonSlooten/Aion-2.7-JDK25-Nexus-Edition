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
package com.aionemu.gameserver.ai2.playerbot;

import com.aionemu.gameserver.ai2.AIName;
import com.aionemu.gameserver.ai2.AITemplate;
import com.aionemu.gameserver.ai2.poll.AIAnswer;
import com.aionemu.gameserver.ai2.poll.AIAnswers;
import com.aionemu.gameserver.ai2.poll.AIQuestion;
import com.aionemu.gameserver.controllers.movement.BotSummonMoveController;
import com.aionemu.gameserver.model.gameobjects.Summon;

/**
 * The minimum viable AI2 for a bot-owned {@link Summon}, needed purely so its move controller can be
 * driven by {@link com.aionemu.gameserver.taskmanager.tasks.MoveTaskManager} - which calls
 * creature.getAi2().poll(AIQuestion.DESTINATION_REACHED) and dispatches MOVE_ARRIVED/MOVE_VALIDATE to
 * creature.getAi2() on every tick - the same 100ms-smooth movement path a bot itself uses via
 * {@link PlayerBotAI}. No Summon anywhere else in this codebase is ever assigned an AI2 at all: real
 * players' summons are commanded entirely explicitly (CM_SUMMON_CASTSPELL/CM_SUMMON_MOVE), with no AI
 * decision loop of their own, so nothing needed one before. Doesn't override handleMoveArrived()/
 * handleMoveValidate() (AITemplate's own no-op defaults are enough - proven safe already, since
 * PlayerBotAI itself never overrides them either) or anything attack/combat related - the pet's actual
 * combat behavior is driven entirely by PlayerBotSummonSelector calling SummonController.useSkill()
 * directly, independent of any AI event dispatch.
 */
@AIName("summonbot")
public class PlayerBotSummonAI extends AITemplate {

	private Summon owner() {
		return (Summon) getOwner();
	}

	@Override
	protected AIAnswer pollInstance(AIQuestion question) {
		// Must short-circuit here exactly like PlayerBotAI does: the inherited default poll handling has
		// Npc-specific casts in its FIGHT/RETURNING/WALKING branches that would ClassCastException on a
		// Summon.
		if (question == AIQuestion.DESTINATION_REACHED) {
			BotSummonMoveController moveController = (BotSummonMoveController) owner().getMoveController();
			boolean arrived = moveController.isAtDestination();
			if (arrived)
				moveController.markArrived();
			return arrived ? AIAnswers.POSITIVE : AIAnswers.NEGATIVE;
		}
		return null;
	}
}
