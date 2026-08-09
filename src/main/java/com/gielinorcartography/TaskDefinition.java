package com.gielinorcartography;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * A task's fixed properties (zone, completion rule). Loaded from tasks.json, bundled into the
 * plugin jar by build.gradle - that file is also read directly by the Python backend, so it's
 * the one place task data is defined.
 */
final class TaskDefinition
{
	enum CompletionType
	{
		HOLD,
		NPC_LOOT_COUNT,
		CHAT_MESSAGE_COUNT,
		LOCATION_STAT_COUNT
	}

	final String id;
	final String displayName;
	final WorldArea area;
	final String region;
	final CompletionType completionType;

	// HOLD
	final int holdSeconds;

	// NPC_LOOT_COUNT
	final int requiredKills;
	final String killTargetLabel;
	final Set<Integer> targetNpcIds;

	// CHAT_MESSAGE_COUNT
	final int requiredActions;
	final String actionTargetLabel;
	final Pattern messagePattern;

	// LOCATION_STAT_COUNT
	final int requiredLaps;
	final Skill statSkill;
	final Set<WorldPoint> finishPoints;

	private TaskDefinition(String id, String displayName, WorldArea area, String region, CompletionType completionType,
		int holdSeconds,
		int requiredKills, String killTargetLabel, Set<Integer> targetNpcIds,
		int requiredActions, String actionTargetLabel, Pattern messagePattern,
		int requiredLaps, Skill statSkill, Set<WorldPoint> finishPoints)
	{
		this.id = id;
		this.displayName = displayName;
		this.area = area;
		this.region = region;
		this.completionType = completionType;
		this.holdSeconds = holdSeconds;
		this.requiredKills = requiredKills;
		this.killTargetLabel = killTargetLabel;
		this.targetNpcIds = targetNpcIds;
		this.requiredActions = requiredActions;
		this.actionTargetLabel = actionTargetLabel;
		this.messagePattern = messagePattern;
		this.requiredLaps = requiredLaps;
		this.statSkill = statSkill;
		this.finishPoints = finishPoints;
	}

	// Short human-readable summary of what claiming this task actually requires, e.g. "Kill 10
	// cows" or "5 laps completed" - shared by the plugin's chat messages and the sidebar's
	// tooltip, so the wording only lives in one place.
	String objectiveSummary()
	{
		switch (completionType)
		{
			case HOLD:
				return "Hold your ground for " + holdSeconds + "s";
			case NPC_LOOT_COUNT:
				// Singular case (killTargetLabel is always generated as a plural, "-s" suffixed)
				// drops the count entirely: "Kill nikita" instead of the grammatically broken
				// "Kill 1 nikitas".
				return requiredKills == 1
					? "Kill " + singularize(killTargetLabel)
					: "Kill " + requiredKills + " " + killTargetLabel;
			case CHAT_MESSAGE_COUNT:
				return requiredActions + " " + actionTargetLabel;
			case LOCATION_STAT_COUNT:
				return requiredLaps + " laps completed";
			default:
				throw new IllegalStateException("No objective summary for " + completionType);
		}
	}

	private static String singularize(String label)
	{
		return label.endsWith("s") ? label.substring(0, label.length() - 1) : label;
	}

	static List<TaskDefinition> loadAll(Gson gson) throws IOException
	{
		try (InputStream in = TaskDefinition.class.getResourceAsStream("/tasks.json"))
		{
			if (in == null)
			{
				throw new IOException("tasks.json resource not found in plugin jar");
			}

			Type listType = new TypeToken<List<TaskJson>>()
			{
			}.getType();

			List<TaskJson> raw;
			try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				raw = gson.fromJson(reader, listType);
			}

			List<TaskDefinition> tasks = new ArrayList<>();
			for (TaskJson task : raw)
			{
				tasks.add(task.toTaskDefinition());
			}

			return tasks;
		}
	}

	private static final class AreaJson
	{
		int x1;
		int y1;
		int x2;
		int y2;
		int plane;

		WorldArea toWorldArea()
		{
			int minX = Math.min(x1, x2);
			int minY = Math.min(y1, y2);
			int width = Math.abs(x1 - x2) + 1;
			int height = Math.abs(y1 - y2) + 1;
			return new WorldArea(minX, minY, width, height, plane);
		}
	}

	private static final class PointJson
	{
		int x;
		int y;
		int plane;

		WorldPoint toWorldPoint()
		{
			return new WorldPoint(x, y, plane);
		}
	}

	private static final class TaskJson
	{
		String id;
		String displayName;
		AreaJson area;
		String region;
		String completionType;

		Integer holdSeconds;

		Integer requiredKills;
		String killTargetLabel;
		List<Integer> targetNpcIds;

		Integer requiredActions;
		String actionTargetLabel;
		String messagePattern;

		Integer requiredLaps;
		String statSkill;
		List<PointJson> finishPoints;

		TaskDefinition toTaskDefinition()
		{
			Set<Integer> npcIds = targetNpcIds == null
				? Collections.emptySet()
				: Collections.unmodifiableSet(new HashSet<>(targetNpcIds));

			Set<WorldPoint> points;
			if (finishPoints == null)
			{
				points = Collections.emptySet();
			}
			else
			{
				Set<WorldPoint> mutablePoints = new HashSet<>();
				for (PointJson point : finishPoints)
				{
					mutablePoints.add(point.toWorldPoint());
				}
				points = Collections.unmodifiableSet(mutablePoints);
			}

			return new TaskDefinition(
				id,
				displayName,
				area.toWorldArea(),
				region == null ? "Other" : region,
				CompletionType.valueOf(completionType),
				holdSeconds == null ? 0 : holdSeconds,
				requiredKills == null ? 0 : requiredKills,
				killTargetLabel,
				npcIds,
				requiredActions == null ? 0 : requiredActions,
				actionTargetLabel,
				messagePattern == null ? null : Pattern.compile(messagePattern),
				requiredLaps == null ? 0 : requiredLaps,
				statSkill == null ? null : Skill.valueOf(statSkill),
				points
			);
		}
	}
}
