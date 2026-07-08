package com.vaultsurvival.plugin.social;

import java.util.*;

public class GroupData {
    public static class PlayerGroup {
        private final int id;
        private final String name;
        private final UUID ownerUuid;
        private final Set<UUID> members = new HashSet<>();
        private final Set<UUID> invites = new HashSet<>();

        public PlayerGroup(int id, String name, UUID ownerUuid) {
            this.id = id; this.name = name; this.ownerUuid = ownerUuid;
            members.add(ownerUuid);
        }
        public int getId() { return id; }
        public String getName() { return name; }
        public UUID getOwnerUuid() { return ownerUuid; }
        public Set<UUID> getMembers() { return Collections.unmodifiableSet(members); }
        public Set<UUID> getInvites() { return Collections.unmodifiableSet(invites); }
        public void addMember(UUID uuid) { members.add(uuid); invites.remove(uuid); }
        public void removeMember(UUID uuid) { members.remove(uuid); }
        public void invite(UUID uuid) { invites.add(uuid); }
        public boolean isInvited(UUID uuid) { return invites.contains(uuid); }
        public boolean isMember(UUID uuid) { return members.contains(uuid); }
    }
}
