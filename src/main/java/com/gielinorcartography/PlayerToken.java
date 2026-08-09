package com.gielinorcartography;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.runelite.client.RuneLite;

/**
 * A random per-installation secret, persisted under the .runelite directory, sent alongside
 * every claim/steal request. The backend binds it to a player name on that name's first-ever
 * claim, then rejects any later claim under that name from a different token - stopping someone
 * from spoofing another player's claims over plain HTTP without their token file. Not real
 * auth (anyone with filesystem access to that token file could still impersonate that
 * installation), just enough to stop casual cheating among LAN players who don't have access to
 * each other's machines.
 */
final class PlayerToken
{
	private static final String DIRECTORY_NAME = "gielinor-cartography";
	private static final String FILE_NAME = "player-token.txt";

	private PlayerToken()
	{
	}

	static String loadOrCreate() throws IOException
	{
		Path dir = RuneLite.RUNELITE_DIR.toPath().resolve(DIRECTORY_NAME);
		Files.createDirectories(dir);

		Path file = dir.resolve(FILE_NAME);
		if (Files.exists(file))
		{
			String existing = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
			if (!existing.isEmpty())
			{
				return existing;
			}
		}

		String token = UUID.randomUUID().toString();
		Files.write(file, token.getBytes(StandardCharsets.UTF_8));
		return token;
	}
}
