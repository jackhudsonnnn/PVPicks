package com.p2picks.app.bets;

/**
 * A card game's rules refusing a move right now (not your turn, nothing to call, too small a raise); the message is
 * for the player. Any other failure inside a game is a bug.
 */
final class NotAllowed extends IllegalStateException {
    NotAllowed(String message) {
        super(message);
    }
}
