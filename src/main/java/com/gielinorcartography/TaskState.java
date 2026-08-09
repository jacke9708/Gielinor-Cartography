package com.gielinorcartography;

/**
 * Mirrors the JSON body returned by GET /task on the backend.
 * Field names must match the backend's JSON keys exactly - Gson maps by field name.
 */
class TaskState
{
	String id;
	String owner;
	Long lastTaken;
	int cooldownSeconds;
}
