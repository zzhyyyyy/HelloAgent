package com.xmu.ShopAssistant.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemoryRepresentationMapper {
    int insert(MemoryRepresentation representation);

    int updateStatusBySnapshot(
            @Param("snapshotId") String snapshotId,
            @Param("status") MemoryRepresentationStatus status
    );

    MemoryRepresentation selectActiveById(
            @Param("tenantId") String tenantId,
            @Param("ownerId") String ownerId,
            @Param("id") String id
    );

    List<MemoryRepresentation> searchActive(
            @Param("tenantId") String tenantId,
            @Param("ownerId") String ownerId,
            @Param("agentId") String agentId,
            @Param("query") String query,
            @Param("limit") int limit
    );
}
