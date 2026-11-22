package com.xmu.ShopAssistant.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemoryNoteMapper {
    int insert(MemoryNote note);

    int update(MemoryNote note);

    MemoryNote selectActiveBySubject(
            @Param("tenantId") String tenantId,
            @Param("ownerId") String ownerId,
            @Param("agentId") String agentId,
            @Param("scope") MemoryScope scope,
            @Param("subject") String subject
    );

    List<MemoryNote> selectActiveByOwner(
            @Param("tenantId") String tenantId,
            @Param("ownerId") String ownerId,
            @Param("agentId") String agentId,
            @Param("limit") int limit
    );

    List<MemoryNote> searchActive(
            @Param("tenantId") String tenantId,
            @Param("ownerId") String ownerId,
            @Param("agentId") String agentId,
            @Param("query") String query,
            @Param("limit") int limit
    );

    int insertEvidence(MemoryNoteEvidence evidence);
}
