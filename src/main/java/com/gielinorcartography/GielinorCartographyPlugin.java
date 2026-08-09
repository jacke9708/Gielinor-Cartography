package com.gielinorcartography;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Gielinor Cartography"
)
public class GielinorCartographyPlugin extends Plugin
{
	// How many consecutive ticks an active attempt tolerates the player being outside the zone
	// before actually cancelling it - covers briefly stepping out mid-fight while a target NPC
	// wanders past the zone boundary, without letting a genuinely abandoned attempt linger.
	private static final int LEAVE_GRACE_TICKS = 5;
	// How often the sidebar panel and map markers re-check every task's status from the server.
	// Not exposed as a config item - nobody but the developer would ever need to change it.
	private static final int REFRESH_INTERVAL_SECONDS = 30;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GielinorCartographyClient apiClient;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executorService;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private WorldMapPointManager worldMapPointManager;

	@Inject
	private Notifier notifier;

	@Inject
	private GielinorCartographyConfig config;

	private GielinorCartographyPanel panel;
	private TaskMapMarkers mapMarkers;
	private NavigationButton navButton;
	private ScheduledFuture<?> refreshFuture;

	// Loaded once in startUp() from tasks.json; a fixed list of singletons for the plugin's
	// lifetime, so reference equality is fine for detecting a change of zone.
	private List<TaskDefinition> tasks = Collections.emptyList();
	// This installation's secret, sent with every claim/steal request - see PlayerToken.
	private String playerToken;
	// Which zone (if any) the player is currently standing in.
	private TaskDefinition currentTask;
	// Only true once we've confirmed (via the server) that the current task is actually
	// claimable right now - avoids running a hold-timer, and later reporting it as "cancelled",
	// for a task that was locked the whole time and could never have been claimed.
	private boolean attemptActive;
	private int attemptTicks;
	private int attemptKills;
	private int attemptActions;
	private int attemptLaps;
	// Baseline xp for LOCATION_STAT_COUNT tasks - only a StatChanged with xp above this counts as
	// a new gain (rather than, say, a redundant event), reset per attempt. -1 means "not set yet".
	private int lastObservedXp;
	// When we last learned the current task is locked, when it unlocks (epoch seconds) - lets us
	// notice it becoming available while just standing in the zone, without needing to leave and
	// re-enter to trigger another check.
	private Long lockedUntil;
	private int ticksOutsideZone;
	private boolean requestInFlight;
	// Set on the first tick a local player exists after login/world-hop, so refreshAll() only
	// runs once per login rather than every tick - see onGameTick.
	private boolean hasRefreshedSinceLogin;
	// Which tasks we owned as of the last refreshTaskStates() call - lets us notice a task
	// dropping out of this set (stolen by someone else) between polls, since ownership never
	// otherwise changes on its own. Empty until the first successful refresh.
	private Set<String> lastOwnedTaskIds = Collections.emptySet();
	// Cached from the last refreshTaskStates() call, so toggling the "show map markers" config
	// can re-render immediately without waiting on (or triggering) a fresh network fetch.
	private Map<String, TaskState> lastTaskStatesById = Collections.emptyMap();

	@Override
	protected void startUp() throws Exception
	{
		tasks = TaskDefinition.loadAll(gson);
		playerToken = PlayerToken.loadOrCreate();
		resetAttempt();
		currentTask = null;
		hasRefreshedSinceLogin = false;

		panel = new GielinorCartographyPanel(this::showOnMap);
		mapMarkers = new TaskMapMarkers(worldMapPointManager);

		navButton = NavigationButton.builder()
			.tooltip("Gielinor Cartography")
			.icon(buildNavIcon())
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		refreshFuture = executorService.scheduleAtFixedRate(this::refreshAll, 0, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		resetAttempt();
		currentTask = null;

		if (refreshFuture != null)
		{
			refreshFuture.cancel(true);
			refreshFuture = null;
		}

		clientToolbar.removeNavigation(navButton);

		// Capture a local reference before nulling the field below - clientThread.invoke() may
		// defer this to run later rather than synchronously, and the lambda would otherwise see
		// the field as already null by the time it actually runs.
		TaskMapMarkers markersToClear = mapMarkers;
		clientThread.invoke(markersToClear::clear);

		panel = null;
		mapMarkers = null;
		navButton = null;
	}

	// Called from the panel's "Map" button (Swing EDT) - a plain designed API call
	// (setWorldMapPositionTarget), not synthetic input. Doesn't force the map open if it's
	// closed; if it's already open, this moves it instantly, same as clicking the marker's own
	// "Focus on" option does.
	private void showOnMap(TaskDefinition task)
	{
		WorldPoint center = TaskMapMarkers.centerOf(task.area);
		clientThread.invoke(() -> client.getWorldMap().setWorldMapPositionTarget(center));
	}

	private static BufferedImage buildNavIcon()
	{
		return ImageUtil.loadImageResource(GielinorCartographyPlugin.class, "icon.png");
	}

	private void refreshAll()
	{
		refreshTaskStates();
		refreshLeaderboard();
	}

	// Fetches every task's current status and pushes it into both the sidebar panel and the
	// world map markers. Called periodically (see startUp()) and once more immediately after
	// this plugin's own completeTask() succeeds, for instant self-claim feedback rather than
	// waiting for the next periodic poll - the periodic poll is still what surfaces other
	// players' claims.
	private void refreshTaskStates()
	{
		apiClient.getAllTaskStates(
			states -> clientThread.invoke(() ->
			{
				// The plugin may have been shut down while this request was in flight - the
				// scheduled future being cancelled doesn't retract a request that already fired.
				TaskMapMarkers currentMapMarkers = mapMarkers;
				GielinorCartographyPanel currentPanel = panel;
				if (currentMapMarkers == null || currentPanel == null)
				{
					return;
				}

				Map<String, TaskState> byId = new HashMap<>();
				for (TaskState state : states)
				{
					byId.put(state.id, state);
				}

				Player localPlayer = client.getLocalPlayer();
				String localPlayerName = localPlayer == null ? null : localPlayer.getName();

				if (localPlayerName != null)
				{
					notifyStolenTasks(byId, localPlayerName);
				}

				lastTaskStatesById = byId;
				if (config.showMapMarkers())
				{
					currentMapMarkers.refresh(tasks, byId, localPlayerName);
				}
				else
				{
					currentMapMarkers.clear();
				}
				SwingUtilities.invokeLater(() -> currentPanel.tasks().update(tasks, byId, localPlayerName));
			}),
			error -> log.debug("Failed to refresh task states: {}", error)
		);
	}

	// Compares this refresh's ownership against the last one, and flags any task that was ours
	// before but no longer is - the only way that happens is someone else claiming it out from
	// under us, since ownership never expires or clears on its own. Also covers thefts that
	// happened while logged out, as long as the client itself stayed open (this plugin's
	// instance, and lastOwnedTaskIds with it, persists across logout/login).
	private void notifyStolenTasks(Map<String, TaskState> byId, String localPlayerName)
	{
		Set<String> ownedNow = new HashSet<>();
		for (Map.Entry<String, TaskState> entry : byId.entrySet())
		{
			if (localPlayerName.equals(entry.getValue().owner))
			{
				ownedNow.add(entry.getKey());
			}
		}

		for (String taskId : lastOwnedTaskIds)
		{
			if (ownedNow.contains(taskId))
			{
				continue;
			}

			TaskState state = byId.get(taskId);
			String newOwner = state == null ? null : state.owner;
			TaskDefinition task = findTaskById(taskId);
			String taskName = task == null ? taskId : task.displayName;

			if (config.stolenTaskChatNotification())
			{
				sendMessage(taskName + " has been taken by " + newOwner + "!");
			}
			if (config.stolenTaskWindowsNotification())
			{
				notifier.notify(taskName + " has been taken by " + newOwner + "!");
			}
		}

		lastOwnedTaskIds = ownedNow;
	}

	private TaskDefinition findTaskById(String taskId)
	{
		for (TaskDefinition task : tasks)
		{
			if (task.id.equals(taskId))
			{
				return task;
			}
		}

		return null;
	}

	// Same shape/reasoning as refreshTaskStates() - see that method's comment.
	private void refreshLeaderboard()
	{
		apiClient.getLeaderboard(
			rankings -> clientThread.invoke(() ->
			{
				GielinorCartographyPanel currentPanel = panel;
				if (currentPanel == null)
				{
					return;
				}

				Player localPlayer = client.getLocalPlayer();
				String localPlayerName = localPlayer == null ? null : localPlayer.getName();
				Integer localTotalLevel = localPlayer == null ? null : client.getTotalLevel();

				SwingUtilities.invokeLater(() -> currentPanel.leaderboard().update(rankings, localPlayerName, localTotalLevel));
			}),
			error -> log.debug("Failed to refresh leaderboard: {}", error)
		);
	}

	private void resetAttempt()
	{
		clearAttemptProgress();
		requestInFlight = false;
	}

	private void clearAttemptProgress()
	{
		attemptActive = false;
		attemptTicks = 0;
		attemptKills = 0;
		attemptActions = 0;
		attemptLaps = 0;
		lastObservedXp = -1;
		lockedUntil = null;
		ticksOutsideZone = 0;
	}

	// Reacts to the "show map markers" toggle immediately rather than waiting up to
	// REFRESH_INTERVAL_SECONDS for the next periodic poll - re-renders from the cached last
	// fetch, no network call needed either way.
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"gielinor-cartography".equals(event.getGroup()) || !"showMapMarkers".equals(event.getKey()))
		{
			return;
		}

		TaskMapMarkers currentMapMarkers = mapMarkers;
		if (currentMapMarkers == null)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			if (config.showMapMarkers())
			{
				Player localPlayer = client.getLocalPlayer();
				String localPlayerName = localPlayer == null ? null : localPlayer.getName();
				currentMapMarkers.refresh(tasks, lastTaskStatesById, localPlayerName);
			}
			else
			{
				currentMapMarkers.clear();
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (requestInFlight)
		{
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			hasRefreshedSinceLogin = false;
			return;
		}

		// The periodic refresh scheduled in startUp() fires immediately with no initial delay,
		// but the client usually isn't logged in yet at that exact moment - that first fetch
		// would see getLocalPlayer() == null and show every owned task as "not owned by you"
		// until the next scheduled refresh happened to land, up to REFRESH_INTERVAL_SECONDS
		// later. Re-fetching on the very first tick a local player actually exists - covering
		// both initial login and world hops - fixes that immediately instead.
		if (!hasRefreshedSinceLogin)
		{
			hasRefreshedSinceLogin = true;
			refreshAll();
		}

		WorldPoint location = localPlayer.getWorldLocation();
		TaskDefinition zoneHere = findTaskAt(location);

		// Course-style tasks are only "entered" at a small start zone, then actually progress far
		// away from it (running the course) - unlike every other type, leaving that zone
		// mid-attempt is expected, not an abandonment. Only treat it as abandoned if the player
		// walks into a *different* task's zone; wandering the course itself (or passing back
		// through the start) doesn't cancel it. onStatChanged drives progress independently of
		// physical zone the rest of the time.
		boolean runningCourseElsewhere = currentTask != null && attemptActive
			&& currentTask.completionType == TaskDefinition.CompletionType.LOCATION_STAT_COUNT
			&& (zoneHere == null || zoneHere == currentTask);
		if (runningCourseElsewhere)
		{
			return;
		}

		// An active attempt tolerates a few ticks outside its zone before actually cancelling -
		// e.g. a target NPC wandering just past the boundary mid-fight. Walking into a
		// *different* task's zone is still treated as an immediate, deliberate switch, same as
		// the course-task exception above.
		if (currentTask != null && attemptActive && zoneHere != currentTask && zoneHere == null)
		{
			ticksOutsideZone++;
			if (ticksOutsideZone <= LEAVE_GRACE_TICKS)
			{
				return;
			}
		}
		else
		{
			ticksOutsideZone = 0;
		}

		if (zoneHere != currentTask)
		{
			if (currentTask != null && attemptActive)
			{
				sendMessage("Left " + currentTask.displayName + " - attempt cancelled.");
			}

			currentTask = zoneHere;
			clearAttemptProgress();

			if (currentTask != null)
			{
				checkZoneAvailability(currentTask);
			}
		}
		else if (currentTask != null && attemptActive && currentTask.completionType == TaskDefinition.CompletionType.HOLD)
		{
			attemptTicks++;
			if (attemptTicks >= holdTicks(currentTask))
			{
				attemptActive = false;
				completeTask(currentTask, localPlayer.getName());
			}
		}
		else if (currentTask != null && !attemptActive && lockedUntil != null && nowEpochSeconds() >= lockedUntil)
		{
			// Still standing in the zone we found locked earlier, and the cooldown we were told
			// about has passed - re-check with the server instead of making the player leave and
			// re-enter to find out it's available.
			lockedUntil = null;
			checkZoneAvailability(currentTask);
		}
	}

	// ServerNpcLoot is sent by the game server itself whenever a kill (or pickpocket) yields loot
	// for the local player - it's an authoritative "you killed this" signal, not a client-side
	// guess, which is why this doesn't need to correlate hitsplats to despawns itself.
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (currentTask == null || !attemptActive || currentTask.completionType != TaskDefinition.CompletionType.NPC_LOOT_COUNT)
		{
			return;
		}

		if (!currentTask.targetNpcIds.contains(event.getComposition().getId()))
		{
			return;
		}

		attemptKills++;
		recordProgress(currentTask, attemptKills);
	}

	// Gathering/production-skill actions (picking, cutting, cooking, weaving, etc.) don't have a
	// dedicated success event - RuneLite's own Woodcutting plugin detects them the same way, by
	// matching the chat message text the game prints on success.
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (currentTask == null || !attemptActive || currentTask.completionType != TaskDefinition.CompletionType.CHAT_MESSAGE_COUNT)
		{
			return;
		}

		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM && type != ChatMessageType.MESBOX)
		{
			return;
		}

		if (!currentTask.messagePattern.matcher(event.getMessage()).matches())
		{
			return;
		}

		attemptActions++;
		recordProgress(currentTask, attemptActions);
	}

	// Lap-based tasks (agility courses etc.) have no "lap completed" event either - this mirrors
	// RuneLite's own Agility plugin: a lap is credited when the tracked skill's xp increases while
	// the player is standing on one of the task's known finish tiles.
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (currentTask == null || !attemptActive || currentTask.completionType != TaskDefinition.CompletionType.LOCATION_STAT_COUNT)
		{
			return;
		}

		if (event.getSkill() != currentTask.statSkill)
		{
			return;
		}

		int xp = event.getXp();
		if (lastObservedXp < 0)
		{
			// First observation just establishes a baseline; the xp gain that produced it may
			// have happened before the attempt started.
			lastObservedXp = xp;
			return;
		}

		if (xp <= lastObservedXp)
		{
			return;
		}
		lastObservedXp = xp;

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || !currentTask.finishPoints.contains(localPlayer.getWorldLocation()))
		{
			return;
		}

		attemptLaps++;
		recordProgress(currentTask, attemptLaps);
	}

	private void recordProgress(TaskDefinition task, int count)
	{
		int required = requiredCount(task);
		if (count < required)
		{
			sendMessage(task.displayName + ": " + count + "/" + required + " " + progressPhrase(task) + ".");
			return;
		}

		attemptActive = false;
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null)
		{
			completeTask(task, localPlayer.getName());
		}
	}

	// The "X killed" / "X cut" / "laps completed" phrase used both mid-attempt ("3/10 cows
	// killed.") and up front when describing the objective ("10 cows killed to claim it.") - one
	// definition of the wording per completion type, not two.
	private static String progressPhrase(TaskDefinition task)
	{
		switch (task.completionType)
		{
			case NPC_LOOT_COUNT:
				return task.killTargetLabel + " killed";
			case CHAT_MESSAGE_COUNT:
				return task.actionTargetLabel;
			case LOCATION_STAT_COUNT:
				return "laps completed";
			default:
				throw new IllegalStateException("No progress phrase for " + task.completionType);
		}
	}

	private static int requiredCount(TaskDefinition task)
	{
		switch (task.completionType)
		{
			case NPC_LOOT_COUNT:
				return task.requiredKills;
			case CHAT_MESSAGE_COUNT:
				return task.requiredActions;
			case LOCATION_STAT_COUNT:
				return task.requiredLaps;
			default:
				throw new IllegalStateException("No required count for " + task.completionType);
		}
	}

	private TaskDefinition findTaskAt(WorldPoint location)
	{
		for (TaskDefinition task : tasks)
		{
			if (task.area.contains(location))
			{
				return task;
			}
		}

		return null;
	}

	private static int holdTicks(TaskDefinition task)
	{
		// A game tick is 0.6s; round the configured hold time up to the nearest tick.
		return (int) Math.round(task.holdSeconds / 0.6);
	}

	private void checkZoneAvailability(TaskDefinition task)
	{
		apiClient.getTaskState(task.id,
			state -> clientThread.invoke(() ->
			{
				if (currentTask != task)
				{
					// Player already left (or moved to another zone) before the response came back.
					return;
				}

				Long unlocksAt = state.lastTaken == null ? null : state.lastTaken + state.cooldownSeconds;
				if (unlocksAt != null && unlocksAt > nowEpochSeconds())
				{
					attemptActive = false;
					lockedUntil = unlocksAt;
					sendMessage(task.displayName + " is locked - owned by " + state.owner + ", available in " + formatDuration(unlocksAt - nowEpochSeconds()) + ".");
				}
				else
				{
					attemptActive = true;
					lockedUntil = null;
					sendMessage(describeOwner(task, state.owner) + " " + task.objectiveSummary() + " to claim it.");
				}
			}),
			error -> clientThread.invoke(() ->
			{
				if (currentTask == task)
				{
					sendMessage("Could not reach the server: " + error);
				}
			})
		);
	}

	private void completeTask(TaskDefinition task, String playerName)
	{
		requestInFlight = true;
		// Read on the client thread, same as playerName above - completeTask() is always called
		// from an already-client-thread-bound context (event handlers, tick handler).
		int totalLevel = client.getTotalLevel();
		apiClient.completeTask(task.id, playerName, totalLevel, playerToken,
			result -> clientThread.invoke(() ->
			{
				requestInFlight = false;
				if (result.stolen && result.stealBonus > 0)
				{
					sendMessage("Stole " + task.displayName + " from " + result.previousOwner + "! +" + result.pointsAwarded
						+ " points (includes +" + result.stealBonus + " neglect bonus).");
				}
				else if (result.stolen)
				{
					sendMessage("Stole " + task.displayName + " from " + result.previousOwner + "! +" + result.pointsAwarded + " points.");
				}
				else
				{
					sendMessage("Claimed " + task.displayName + "! +" + result.pointsAwarded + " points.");
				}
				refreshTaskStates();
				refreshLeaderboard();
			}),
			(owner, unlocksAt) -> clientThread.invoke(() ->
			{
				requestInFlight = false;
				if (currentTask == task)
				{
					lockedUntil = unlocksAt;
				}
				sendMessage(task.displayName + " is still locked (owned by " + owner + "). Available in " + formatDuration(unlocksAt - nowEpochSeconds()) + ".");
			}),
			error -> clientThread.invoke(() ->
			{
				requestInFlight = false;
				sendMessage("Could not reach the server: " + error);
			})
		);
	}

	private static String describeOwner(TaskDefinition task, String owner)
	{
		return owner == null
			? task.displayName + " is unclaimed."
			: task.displayName + " is currently owned by " + owner + ".";
	}

	private static long nowEpochSeconds()
	{
		return System.currentTimeMillis() / 1000;
	}

	private static String formatDuration(long seconds)
	{
		seconds = Math.max(seconds, 0);
		return (seconds / 60) + "m " + (seconds % 60) + "s";
	}

	private void sendMessage(String text)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", text, null);
	}

	@Provides
	GielinorCartographyConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GielinorCartographyConfig.class);
	}
}
