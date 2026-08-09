package com.gielinorcartography;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GielinorCartographyPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GielinorCartographyPlugin.class);
		RuneLite.main(args);
	}
}
