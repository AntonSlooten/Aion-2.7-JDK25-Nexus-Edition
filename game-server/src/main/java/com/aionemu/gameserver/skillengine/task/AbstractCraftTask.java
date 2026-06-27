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
package com.aionemu.gameserver.skillengine.task;

import java.util.Random;

import com.aionemu.commons.utils.Rnd;
import com.aionemu.gameserver.model.gameobjects.VisibleObject;
import com.aionemu.gameserver.model.gameobjects.player.Player;

/**
 * @author ATracer, synchro2
 */
public abstract class AbstractCraftTask extends AbstractInteractionTask {

	protected int completeValue = 100;
	protected int currentSuccessValue;
	protected int currentFailureValue;
	protected int skillLvlDiff;
	private final Random rng = new Random();
	private final float maxSuccessAmount = 60f;
    private final float maxFailureAmount = 60f;

	/**
	 * @param requestor
	 * @param responder
	 * @param successValue
	 * @param failureValue
	 */
	public AbstractCraftTask(Player requestor, VisibleObject responder, int skillLvlDiff) {
		super(requestor, responder);
		this.skillLvlDiff = Math.abs(skillLvlDiff);
	}

	@Override
	protected boolean onInteraction() {
		if (currentSuccessValue >= completeValue) {
			return onSuccessFinish();
		}
		if (currentFailureValue >= completeValue) {
			onFailureFinish();
			return true;
		}

		analyzeInteraction();

		sendInteractionUpdate();
		return false;
	}

	/**
	 * Perform interaction calculation.
	 * If the skill level is greater than 40, lets just give it to them immediately. This costs about 3.5 Second of time. 
	 * At skillLvlDiff of 0 you have about a 50% chance of success per interation, and that drops linearly to 0 at skillLvlDiff of 40
	 * There is also a "maxSuccessAmount" which is the maximum value that can be added during the interation. 
	 * The max amount is multiplied by a random value between your chance of success and 1 to add some random effect to the gathering mechanism.
	 * The fail rate has the same thing, but has a higher chance for a lower maxFailureAmmount, by means of a widening band of RNG (1-successRate)
	 * Generally speaking it makes it hard to fail, but the gathering time reduces signficantly as you get better.
	 * 
	 * The end result here respects peoples time, and allows some skill levels to make a difference. 
	 */
	private void analyzeInteraction() {

		if(Math.abs(skillLvlDiff) >= 40 )
		{
			currentSuccessValue += completeValue;
			return;
		}
		double successRate = successMap(skillLvlDiff);
		if(rng.nextDouble() <= successRate) {
			currentSuccessValue += maxSuccessAmount * rng.nextDouble(successRate, 1);
			currentSuccessValue = Math.min(currentSuccessValue, completeValue);
		}
		else {
			currentFailureValue += maxFailureAmount * rng.nextDouble((1-successRate), 1);
			currentFailureValue = Math.min(currentFailureValue, completeValue);
		}

	}
	/**
	 * Maps the chance of success for each "interaction, and linearly extrapolates it between 50 -> 100%
	 * @param skillDifference
	 * @return % chance of success
	 */
	static double successMap(double skillDifference) {

		double slope = (1 - 0.5) / (40 - 0);
		return 0.5 + slope * (skillDifference - 0);

	}

	protected abstract void sendInteractionUpdate();

	protected abstract boolean onSuccessFinish();

	protected abstract void onFailureFinish();
}