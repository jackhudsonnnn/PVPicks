package com.p2picks.app.accounts;

import java.util.UUID;

/**
 * Published inside the transaction that creates a player account (the admin's New player), so listeners can set
 * the player up in the same transaction: the LAN party seats them ({@code lan.PartyTable}).
 */
public record PlayerCreated(UUID playerId, String username) {}
