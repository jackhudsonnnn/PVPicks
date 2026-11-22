package com.p2picks.app.lan;

import com.p2picks.app.accounts.PlayerCreated;
import com.p2picks.app.social.Friendships;
import com.p2picks.app.tables.TableProvisioning;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seats every new player at the party table, inside the transaction that creates them, so a player is never
 * created without their seat. The first player creates the table ("New Table", which they can rename) and hosts it.
 * If the table is later deleted (say its players were deleted while testing), the next player starts a fresh one.
 */
@Component
@ConditionalOnProperty(name = "pvpicks.lan.party.enabled", havingValue = "true")
class PartyTable {
    static final String NAME = "New Table";
    static final String EMOJI = "🏈";

    private final JdbcTemplate jdbc;
    private final TableProvisioning tables;
    private final Friendships friendships;

    PartyTable(JdbcTemplate jdbc, TableProvisioning tables, Friendships friendships) {
        this.jdbc = jdbc;
        this.tables = tables;
        this.friendships = friendships;
    }

    @EventListener
    @Transactional
    void seat(PlayerCreated created) {
        // The row lock makes concurrent New player requests take turns: exactly one becomes the host.
        jdbc.execute("LOCK TABLE lan_party IN EXCLUSIVE MODE");
        Optional<UUID> party = table();
        if (party.isPresent() && tables.isOpen(party.get())) {
            List<UUID> already = tables.members(party.get());
            if (tables.seat(party.get(), created.playerId())) {
                for (UUID member : already) friendships.befriend(created.playerId(), member);
            }
            return;
        }
        UUID tableId = tables.createHosted(created.playerId(), NAME, EMOJI);
        jdbc.update("""
                INSERT INTO lan_party (table_id) VALUES (?)
                ON CONFLICT (singleton) DO UPDATE SET table_id = EXCLUDED.table_id, created_at = now()
                """, tableId);
    }

    /** The party table's id, once the first player has been created (it may have been deleted since). */
    Optional<UUID> table() {
        return jdbc.queryForList("SELECT table_id FROM lan_party", UUID.class).stream().findFirst();
    }
}
