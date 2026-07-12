package com.vaultsurvival.plugin.districts;

import java.util.Map;
import java.util.UUID;

public record DistrictFoundingPetition(UUID petitionUuid,UUID founderUuid,String districtName,String status,
                                       DistrictData.BlockClaim claim,UUID contractUuid,long createdAt,long expiresAt,
                                       Map<UUID,Participant> participants){
    public DistrictFoundingPetition{participants=Map.copyOf(participants);}
    public long acceptedCount(){return participants.values().stream().filter(p->p.status()==ParticipantStatus.ACCEPTED).count();}
    public record Participant(UUID playerUuid,DistrictData.DistrictRole role,ParticipantStatus status){}
    public enum ParticipantStatus{INVITED,ACCEPTED,DECLINED}
}
