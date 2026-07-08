package com.vaultsurvival.plugin.social;

import java.util.List;
import java.util.UUID;

public interface GroupService {
    GroupData.PlayerGroup createGroup(UUID owner, String name);
    boolean disbandGroup(int groupId, UUID actor);
    boolean inviteMember(int groupId, UUID inviter, UUID target);
    boolean acceptInvite(int groupId, UUID player);
    boolean kickMember(int groupId, UUID actor, UUID target);
    boolean leaveGroup(int groupId, UUID player);
    GroupData.PlayerGroup getGroup(int groupId);
    GroupData.PlayerGroup getPlayerGroup(UUID player);
    List<GroupData.PlayerGroup> getAllGroups();
    void loadAll();
}
