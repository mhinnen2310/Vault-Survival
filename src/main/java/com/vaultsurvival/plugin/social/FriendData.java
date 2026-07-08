package com.vaultsurvival.plugin.social;

import java.util.*;

/**
 * Data models for the Friends system.
 */
public class FriendData {
    public static class FriendEntry {
        private final UUID playerUuid;
        private final String friendName;
        private final long since;

        public FriendEntry(UUID playerUuid, String friendName, long since) {
            this.playerUuid = playerUuid; this.friendName = friendName; this.since = since;
        }
        public UUID getPlayerUuid() { return playerUuid; }
        public String getFriendName() { return friendName; }
        public long getSince() { return since; }
    }
}
