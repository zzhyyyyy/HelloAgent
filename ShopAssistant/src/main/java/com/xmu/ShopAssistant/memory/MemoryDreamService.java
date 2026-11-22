package com.xmu.ShopAssistant.memory;

public interface MemoryDreamService {
    String submit(String tenantId, String ownerId, String agentId, String instructions);

    MemorySnapshot execute(String jobId);

    void approveProposal(String proposalId);

    void rejectProposal(String proposalId);

    void activate(String snapshotId);

    MemorySnapshot rollback(String activeSnapshotId);
}
