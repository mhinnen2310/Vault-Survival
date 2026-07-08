package com.vaultsurvival.plugin.social;

import java.util.List;
import java.util.UUID;

public interface FriendService {
    boolean addFriend(UUID player, UUID friend);
    boolean removeFriend(UUID player, UUID friend);
    List<FriendData.FriendEntry> getFriends(UUID player);
    boolean areFriends(UUID a, UUID b);
    void loadAll();
}
