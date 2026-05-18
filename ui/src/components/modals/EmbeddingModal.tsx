import React, { useState } from "react";
import {
  Modal,
  Input,
  Select,
  Button,
  Space,
  message,
  Typography,
  Spin,
  Tabs,
  Divider,
} from "antd";
import {
  BarcodeOutlined,
  CopyOutlined,
  ExperimentOutlined,
} from "@ant-design/icons";
import { post } from "../../api/http.ts";

const { Text } = Typography;

interface EmbedResult {
  embedding: number[];
  dimensions: number;
}

interface CosineSimilarityResult {
  similarity: number;
  dimensions: number;
}

const DIMENSION_OPTIONS = [
  { value: 256, label: "256" },
  { value: 512, label: "512" },
  { value: 768, label: "768" },
  { value: 1024, label: "1024" },
];

const EmbeddingModal: React.FC = () => {
  const [open, setOpen] = useState(false);
  const [dimensions, setDimensions] = useState<number>(512);

  // 单文本 Embedding
  const [text, setText] = useState("");
  const [embedLoading, setEmbedLoading] = useState(false);
  const [embedResult, setEmbedResult] = useState<EmbedResult | null>(null);

  // 余弦相似度
  const [text1, setText1] = useState("");
  const [text2, setText2] = useState("");
  const [cosLoading, setCosLoading] = useState(false);
  const [cosResult, setCosResult] = useState<CosineSimilarityResult | null>(null);

  const handleOpen = () => {
    setDimensions(512);
    setText("");
    setEmbedResult(null);
    setText1("");
    setText2("");
    setCosResult(null);
    setOpen(true);
  };

  const formatVectorPreview = (vec: number[], showCount: number) => {
    const preview = vec.slice(0, showCount).map((v) => v.toFixed(6));
    return `[${preview.join(", ")}${vec.length > showCount ? ", ..." : ""}]`;
  };

  const copyEmbedding = async () => {
    if (!embedResult) return;
    try {
      await navigator.clipboard.writeText(
        JSON.stringify(embedResult.embedding, null, 2),
      );
      message.success("已复制到剪贴板");
    } catch {
      message.error("复制失败");
    }
  };

  const handleEmbed = async () => {
    if (!text.trim()) {
      message.warning("请输入需要 Embedding 的文本");
      return;
    }
    setEmbedLoading(true);
    setEmbedResult(null);
    try {
      const resp = await post<EmbedResult>("/rag/embed", {
        text: text.trim(),
        dimensions,
      });
      setEmbedResult(resp);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "Embedding 请求失败");
    } finally {
      setEmbedLoading(false);
    }
  };

  const handleCosineSimilarity = async () => {
    if (!text1.trim() || !text2.trim()) {
      message.warning("请同时输入文本 1 和文本 2");
      return;
    }
    setCosLoading(true);
    setCosResult(null);
    try {
      const resp = await post<CosineSimilarityResult>("/rag/cosine-similarity", {
        text1: text1.trim(),
        text2: text2.trim(),
        dimensions,
      });
      setCosResult(resp);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "余弦相似度计算失败");
    } finally {
      setCosLoading(false);
    }
  };

  return (
    <>
      <Button
        type="default"
        icon={<BarcodeOutlined />}
        onClick={handleOpen}
        size="small"
      >
        Embedding
      </Button>
      <Modal
        title="Embedding 向量工具"
        open={open}
        onCancel={() => setOpen(false)}
        width={760}
        footer={null}
        destroyOnClose
      >
        {/* 维度选择 - 全局 */}
        <div className="flex items-center gap-4 mb-4">
          <Space>
            <span className="text-sm text-gray-500">向量维度:</span>
            <Select
              value={dimensions}
              onChange={setDimensions}
              options={DIMENSION_OPTIONS}
              style={{ width: 100 }}
            />
          </Space>
        </div>

        <Tabs
          items={[
            {
              key: "single",
              label: "单文本 Embedding",
              children: (
                <Space direction="vertical" className="w-full" size="middle">
                  <Input.TextArea
                    placeholder="请输入需要 Embedding 的文本..."
                    value={text}
                    onChange={(e) => setText(e.target.value)}
                    rows={4}
                  />
                  <Button
                    type="primary"
                    icon={<BarcodeOutlined />}
                    onClick={handleEmbed}
                    loading={embedLoading}
                    block
                    size="large"
                  >
                    {embedLoading ? "生成 Embedding 中..." : "生成 Embedding"}
                  </Button>
                  {embedLoading && (
                    <div className="flex justify-center py-8">
                      <Spin tip="正在调用 Embedding 模型..." />
                    </div>
                  )}
                  {embedResult && (
                    <div className="border border-gray-200 rounded-lg p-4 bg-gray-50">
                      <div className="flex items-center justify-between mb-3">
                        <Text strong className="text-sm">
                          Embedding 向量（共 {embedResult.dimensions} 维）
                        </Text>
                        <Button type="text" size="small" icon={<CopyOutlined />} onClick={copyEmbedding}>
                          复制
                        </Button>
                      </div>
                      <div className="bg-white rounded border border-gray-100 p-3 max-h-48 overflow-y-auto">
                        <pre className="text-xs font-mono whitespace-pre-wrap break-words leading-relaxed">
                          {formatVectorPreview(embedResult.embedding, 20)}
                        </pre>
                      </div>
                      <div className="mt-2 text-xs text-gray-400">
                        预览前 20 个值，完整向量共 {embedResult.dimensions} 维
                      </div>
                    </div>
                  )}
                </Space>
              ),
            },
            {
              key: "cosine",
              label: "余弦相似度",
              children: (
                <Space direction="vertical" className="w-full" size="middle">
                  <Input.TextArea
                    placeholder="输入文本 1..."
                    value={text1}
                    onChange={(e) => setText1(e.target.value)}
                    rows={3}
                  />
                  <Input.TextArea
                    placeholder="输入文本 2..."
                    value={text2}
                    onChange={(e) => setText2(e.target.value)}
                    rows={3}
                  />
                  <Button
                    type="primary"
                    icon={<ExperimentOutlined />}
                    onClick={handleCosineSimilarity}
                    loading={cosLoading}
                    block
                    size="large"
                  >
                    {cosLoading ? "计算中..." : "计算余弦相似度"}
                  </Button>
                  {cosResult && (
                    <div className="border border-gray-200 rounded-lg p-5 bg-gray-50 text-center">
                      <Text type="secondary" className="text-sm">余弦相似度</Text>
                      <div className="text-3xl font-bold mt-1" style={{ color: cosResult.similarity > 0.5 ? '#22c55e' : cosResult.similarity > 0 ? '#eab308' : '#ef4444' }}>
                        {cosResult.similarity.toFixed(6)}
                      </div>
                      <div className="text-xs text-gray-400 mt-2">
                        向量维度: {cosResult.dimensions}
                      </div>
                    </div>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Modal>
    </>
  );
};

export default EmbeddingModal;
