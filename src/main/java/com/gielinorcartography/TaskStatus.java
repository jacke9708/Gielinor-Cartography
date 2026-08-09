package com.gielinorcartography;

import java.awt.Color;

/**
 * The single source of truth for a task's status color, shared by the sidebar panel and the
 * world map markers so they can never disagree on what a color means.
 */
enum TaskStatus
{
	UNCLAIMED,
	AVAILABLE,
	LOCKED,
	OWNED_BY_YOU;

	static TaskStatus compute(TaskState state, String localPlayerName, long nowEpochSeconds)
	{
		if (state.owner == null)
		{
			return UNCLAIMED;
		}

		if (state.owner.equals(localPlayerName))
		{
			return OWNED_BY_YOU;
		}

		long unlocksAt = state.lastTaken + state.cooldownSeconds;
		return nowEpochSeconds >= unlocksAt ? AVAILABLE : LOCKED;
	}

	Color color()
	{
		switch (this)
		{
			case UNCLAIMED:
				return Color.GRAY;
			case AVAILABLE:
				return Color.RED;
			case LOCKED:
				return Color.BLACK;
			case OWNED_BY_YOU:
				return Color.GREEN;
			default:
				throw new IllegalStateException("Unhandled status: " + this);
		}
	}
}
