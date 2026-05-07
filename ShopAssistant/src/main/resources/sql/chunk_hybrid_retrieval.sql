-- 混合检索（BM25 + 向量）索引增强脚本
-- 说明：
-- 1) 当前代码中 BM25 查询使用 to_tsvector(...) 表达式，未依赖新增列。
-- 2) 执行本脚本后，可显著提升全文检索与向量检索性能。

-- 全文检索表达式索引（与 SQL 中表达式保持一致）
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_fts
    ON chunk_bge_m3
    USING GIN (to_tsvector('simple', COALESCE(content, '') || ' ' || COALESCE(metadata::text, '')));

-- 向量检索索引（cosine distance）
-- 若数据量较大，可调 lists 参数并执行 ANALYZE
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_embedding_ivfflat
    ON chunk_bge_m3
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

ANALYZE chunk_bge_m3;
