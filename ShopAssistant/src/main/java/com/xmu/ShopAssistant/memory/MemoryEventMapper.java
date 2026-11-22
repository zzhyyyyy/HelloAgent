package com.xmu.ShopAssistant.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemoryEventMapper {
    int insert(MemoryEvent event);

    List<MemoryEvent> selectByOwner(
            @Param("tenantId") String tenantId,
            @Param("ownerId") String ownerId,
            @Param("limit") int limit
    );

    List<MemoryEvent> selectBySession(
            @Param("tenantId") String tenantId,
            @Param("ownerId") String ownerId,
            @Param("sessionId") String sessionId,
            @Param("limit") int limit
    );
}
