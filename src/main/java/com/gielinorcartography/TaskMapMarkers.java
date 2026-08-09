package com.gielinorcartography;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

/**
 * Owns the set of world-map dots showing each task's status. refresh()/clear() must only be
 * called from the client thread - this class doesn't enforce that itself, the plugin does.
 */
class TaskMapMarkers
{
	private static final int DOT_DIAMETER = 8;

	private final WorldMapPointManager worldMapPointManager;

	TaskMapMarkers(WorldMapPointManager worldMapPointManager)
	{
		this.worldMapPointManager = worldMapPointManager;
	}

	void refresh(List<TaskDefinition> tasks, Map<String, TaskState> statesById, String localPlayerName)
	{
		clear();

		long now = System.currentTimeMillis() / 1000;
		for (TaskDefinition task : tasks)
		{
			TaskState state = statesById.get(task.id);
			TaskStatus status = state == null ? TaskStatus.UNCLAIMED : TaskStatus.compute(state, localPlayerName, now);
			worldMapPointManager.add(new TaskMapPoint(centerOf(task.area), status, task));
		}
	}

	void clear()
	{
		worldMapPointManager.removeIf(point -> point instanceof TaskMapPoint);
	}

	// Package-private so the plugin can reuse this exact calculation for the sidebar's
	// "show on map" button, rather than duplicating it.
	static WorldPoint centerOf(WorldArea area)
	{
		return new WorldPoint(
			area.getX() + area.getWidth() / 2,
			area.getY() + area.getHeight() / 2,
			area.getPlane());
	}

	private static final class TaskMapPoint extends WorldMapPoint
	{
		TaskMapPoint(WorldPoint worldPoint, TaskStatus status, TaskDefinition task)
		{
			super(worldPoint, dotImage(status.color()));
			// The right-click "Focus on X" menu entry needs the location name (that's what
			// you're choosing to jump to), but the hover tooltip is more useful showing what
			// actually needs doing here - the location is already obvious from where the dot is.
			setName(task.displayName);
			setTooltip(task.objectiveSummary());
			setSnapToEdge(true);
			// Gives a right-click "Focus on [name]" option once the map is open, same as real
			// OSRS quest markers - the closest legitimate equivalent to a "show on map" button
			// (there's no supported way to force the map open from the sidebar itself).
			setJumpOnClick(true);
		}

		private static BufferedImage dotImage(Color color)
		{
			BufferedImage image = new BufferedImage(DOT_DIAMETER, DOT_DIAMETER, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			g.setColor(color);
			g.fillOval(0, 0, DOT_DIAMETER, DOT_DIAMETER);
			g.setColor(Color.BLACK);
			g.drawOval(0, 0, DOT_DIAMETER - 1, DOT_DIAMETER - 1);
			g.dispose();
			return image;
		}
	}
}
