package com.gielinorcartography;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to the Gielinor Cartography backend over HTTP. All calls are async (OkHttp's enqueue()) -
 * callbacks run on OkHttp's thread pool, never the client thread, so callers must hop back via
 * ClientThread before touching anything from the game client.
 */
@Slf4j
@Singleton
class GielinorCartographyClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int HTTP_LOCKED = 423;

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final GielinorCartographyConfig config;

	@Inject
	GielinorCartographyClient(OkHttpClient okHttpClient, Gson gson, GielinorCartographyConfig config)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.config = config;
	}

	void getTaskState(String taskId, Consumer<TaskState> onSuccess, Consumer<String> onError)
	{
		HttpUrl url = HttpUrl.parse(config.serverUrl() + "/task")
			.newBuilder()
			.addQueryParameter("id", taskId)
			.build();
		Request request = new Request.Builder()
			.url(url)
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept(e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						onError.accept("HTTP " + r.code());
						return;
					}

					onSuccess.accept(gson.fromJson(r.body().charStream(), TaskState.class));
				}
				catch (Exception e)
				{
					log.warn("Failed to parse task state", e);
					onError.accept(e.getMessage());
				}
			}
		});
	}

	void getAllTaskStates(Consumer<List<TaskState>> onSuccess, Consumer<String> onError)
	{
		Request request = new Request.Builder()
			.url(config.serverUrl() + "/tasks")
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept(e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						onError.accept("HTTP " + r.code());
						return;
					}

					Type listType = new TypeToken<List<TaskState>>()
					{
					}.getType();
					onSuccess.accept(gson.fromJson(r.body().charStream(), listType));
				}
				catch (Exception e)
				{
					log.warn("Failed to parse task states", e);
					onError.accept(e.getMessage());
				}
			}
		});
	}

	void getLeaderboard(Consumer<List<PlayerRanking>> onSuccess, Consumer<String> onError)
	{
		Request request = new Request.Builder()
			.url(config.serverUrl() + "/leaderboard")
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept(e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						onError.accept("HTTP " + r.code());
						return;
					}

					Type listType = new TypeToken<List<PlayerRanking>>()
					{
					}.getType();
					onSuccess.accept(gson.fromJson(r.body().charStream(), listType));
				}
				catch (Exception e)
				{
					log.warn("Failed to parse leaderboard", e);
					onError.accept(e.getMessage());
				}
			}
		});
	}

	void completeTask(String taskId, String player, Integer totalLevel, String playerToken, Consumer<CompletionResult> onSuccess, BiConsumer<String, Long> onLocked, Consumer<String> onError)
	{
		String json = gson.toJson(new CompleteRequest(taskId, player, totalLevel, playerToken));
		RequestBody body = RequestBody.create(JSON, json);
		Request request = new Request.Builder()
			.url(config.serverUrl() + "/complete")
			.post(body)
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept(e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == HTTP_LOCKED)
					{
						LockedInfo info = gson.fromJson(r.body().charStream(), LockedInfo.class);
						onLocked.accept(info.owner, info.unlocksAt);
						return;
					}

					if (!r.isSuccessful())
					{
						onError.accept("HTTP " + r.code());
						return;
					}

					onSuccess.accept(gson.fromJson(r.body().charStream(), CompletionResult.class));
				}
				catch (Exception e)
				{
					log.warn("Failed to parse completion result", e);
					onError.accept(e.getMessage());
				}
			}
		});
	}

	// playerToken is a per-installation secret (see PlayerToken) the backend binds to a player
	// name on that name's first claim - basic protection against spoofing someone else's claims
	// over plain HTTP, not real account auth.
	private static class CompleteRequest
	{
		final String taskId;
		final String player;
		final Integer totalLevel;
		final String playerToken;

		CompleteRequest(String taskId, String player, Integer totalLevel, String playerToken)
		{
			this.taskId = taskId;
			this.player = player;
			this.totalLevel = totalLevel;
			this.playerToken = playerToken;
		}
	}
}
