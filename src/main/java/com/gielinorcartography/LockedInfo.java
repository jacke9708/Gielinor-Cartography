package com.gielinorcartography;

/**
 * Mirrors the JSON body returned when POST /complete is rejected (HTTP 423) because
 * the task is still on cooldown.
 */
class LockedInfo
{
	String error;
	Long unlocksAt;
	String owner;
}
