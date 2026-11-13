package com.p2picks.app.delivery;

import java.util.Set;
import java.util.UUID;

/** Who may learn about a change, looked up when the event is delivered (not when it was written). */
interface Audiences {
    /** Both parties of every receipt line in a points settlement. */
    Set<UUID> settlementParties(UUID settlementId);

    /** Current members of a non-archived table. */
    Set<UUID> tableMembers(UUID tableId);

    /** Current hosts of a non-archived table. */
    Set<UUID> tableHosts(UUID tableId);

    /** Users with a pending invitation to the table. */
    Set<UUID> pendingInvitees(UUID tableId);

    /** Members snapshotted when the table was deleted. */
    Set<UUID> archiveMembers(UUID tableId);

    /** People who share a current table or a receipt with the user, excluding blocks in either direction. */
    Set<UUID> profileAudience(UUID userId);
}
