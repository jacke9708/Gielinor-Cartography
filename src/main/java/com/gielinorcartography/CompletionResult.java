package com.gielinorcartography;

/**
 * Mirrors the JSON body returned by a successful POST /complete on the backend.
 */
class CompletionResult
{
	String id;
	String owner;
	Long lastTaken;
	int cooldownSeconds;
	int pointsAwarded;
	int stealBonus;
	boolean stolen;
	String previousOwner;
}
