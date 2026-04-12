package io.github.ensgijs.dbm.util;

import io.github.ensgijs.dbm.sql.SqlDialect;
import io.github.ensgijs.dbm.sql.UpsertStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UpsertStatementTest {

    @Test
    void builderSanity() {
        UpsertStatement upsert = UpsertStatement.builder("rtp_queue")
                .keys("player_uuid")
                .values("player_name", "queued_at", "shard_name")
                .build();
        assertEquals(
                "INSERT INTO rtp_queue (player_uuid, player_name, queued_at, shard_name) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), queued_at = VALUES(queued_at), shard_name = VALUES(shard_name)",
                upsert.sql(SqlDialect.MYSQL)
        );
        assertEquals(
                "INSERT INTO rtp_queue (player_uuid, player_name, queued_at, shard_name) VALUES (?, ?, ?, ?) ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, queued_at = excluded.queued_at, shard_name = excluded.shard_name",
                upsert.sql(SqlDialect.SQLITE)
        );
    }
}
