import React, { useState, useEffect } from "react";
import {
  Modal,
  InputNumber,
  Input,
  Button,
  Select,
  Table,
  Card,
  Statistic,
  Row,
  Col,
  Space,
  message,
  Empty,
  Tabs,
  Tag,
  Typography,
} from "antd";
import {
  ExperimentOutlined,
  PlusOutlined,
  DeleteOutlined,
  HistoryOutlined,
} from "@ant-design/icons";
import { evaluateRag, getKnowledgeBases } from "../../api/api.ts";
import type { RagEvaluationCase, RagEvaluationResponse } from "../../api/api.ts";

const { Text } = Typography;

/* ---------- localStorage 历史记录 ---------- */
interface EvalHistoryEntry {
  id: string;
  timestamp: string;
  topK: number;
  cases: RagEvaluationCase[];
  result: RagEvaluationResponse;
}

const HISTORY_KEY = "rag_eval_history";

function loadHistory(): EvalHistoryEntry[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function saveHistory(entry: EvalHistoryEntry) {
  const list = loadHistory();
  list.unshift(entry);
  localStorage.setItem(HISTORY_KEY, JSON.stringify(list.slice(0, 50)));
}

/* ---------- 组件 ---------- */
const RagEvaluationModal: React.FC = () => {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [topK, setTopK] = useState<number>(3);
  const [kbList, setKbList] = useState<{ id: string; name: string }[]>([]);
  const [evalCases, setEvalCases] = useState<RagEvaluationCase[]>([
    { kbId: "", query: "", relevantDocIds: [] },
  ]);
  const [result, setResult] = useState<RagEvaluationResponse | null>(null);
  const [history, setHistory] = useState<EvalHistoryEntry[]>([]);

  // 拉取知识库列表
  const loadKbList = async () => {
    try {
      const resp = await getKnowledgeBases();
      setKbList(resp.knowledgeBases.map((kb) => ({ id: kb.id, name: kb.name })));
    } catch {
      setKbList([]);
    }
  };

  const handleOpen = async () => {
    setTopK(3);
    setEvalCases([{ kbId: "", query: "", relevantDocIds: [] }]);
    setResult(null);
    setHistory(loadHistory());
    await loadKbList();
    setOpen(true);
  };

  /* ---- 测试用例操作 ---- */
  const updateCase = (idx: number, field: keyof RagEvaluationCase, value: unknown) => {
    setEvalCases((prev) => {
      const updated = [...prev];
      (updated[idx] as Record<string, unknown>)[field] = value;
      return updated;
    });
  };

  const addCase = () => {
    setEvalCases((prev) => [...prev, { kbId: "", query: "", relevantDocIds: [] }]);
  };

  const removeCase = (idx: number) => {
    setEvalCases((prev) => (prev.length <= 1 ? prev : prev.filter((_, i) => i !== idx)));
  };

  /* ---- 文档 ID 行操作 ---- */
  const addDocId = (caseIdx: number) => {
    setEvalCases((prev) => {
      const updated = [...prev];
      updated[caseIdx] = {
        ...updated[caseIdx],
        relevantDocIds: [...updated[caseIdx].relevantDocIds, ""],
      };
      return updated;
    });
  };

  const updateDocId = (caseIdx: number, docIdx: number, value: string) => {
    setEvalCases((prev) => {
      const updated = [...prev];
      const ids = [...updated[caseIdx].relevantDocIds];
      ids[docIdx] = value;
      updated[caseIdx] = { ...updated[caseIdx], relevantDocIds: ids };
      return updated;
    });
  };

  const removeDocId = (caseIdx: number, docIdx: number) => {
    setEvalCases((prev) => {
      const updated = [...prev];
      const ids = updated[caseIdx].relevantDocIds.filter((_, i) => i !== docIdx);
      updated[caseIdx] = { ...updated[caseIdx], relevantDocIds: ids };
      return updated;
    });
  };

  /* ---- 执行评估 ---- */
  const handleEvaluate = async () => {
    const validCases = evalCases.filter(
      (c) => c.kbId && c.query && c.relevantDocIds.some((id) => id.trim()),
    );
    if (validCases.length === 0) {
      message.warning("请至少添加一个有效的测试用例");
      return;
    }

    // 清洗空字符串
    const cleaned = validCases.map((c) => ({
      ...c,
      relevantDocIds: c.relevantDocIds.filter((id) => id.trim()),
    }));

    setLoading(true);
    setResult(null);
    try {
      const resp = await evaluateRag({ topK, cases: cleaned });
      setResult(resp);
      // 保存历史
      saveHistory({
        id: Date.now().toString(),
        timestamp: new Date().toLocaleString("zh-CN"),
        topK,
        cases: cleaned,
        result: resp,
      });
      setHistory(loadHistory());
    } catch (error) {
      message.error(error instanceof Error ? error.message : "评估执行失败");
    } finally {
      setLoading(false);
    }
  };

  /* ---- 查看历史 ---- */
  const viewHistoryEntry = (entry: EvalHistoryEntry) => {
    setTopK(entry.topK);
    setEvalCases(entry.cases);
    setResult(entry.result);
  };

  /* ---- 渲染 ---- */
  const kbOptions = kbList.map((kb) => ({ value: kb.id, label: kb.name }));

  const metricsList = result
    ? [
        { label: "Hit Rate@K", value: result.metrics.hitRateAtK },
        { label: "Avg Recall@K", value: result.metrics.avgRecallAtK },
        { label: "MRR@K", value: result.metrics.mrrAtK },
        { label: "nDCG@K", value: result.metrics.ndcgAtK },
      ]
    : [];

  const caseResultColumns = [
    { title: "查询语句", dataIndex: "query", key: "query", ellipsis: true },
    {
      title: "Hit@K",
      dataIndex: "hitAtK",
      key: "hitAtK",
      width: 80,
      render: (v: number) =>
        v > 0 ? (
          <span className="text-green-500">&#10003;</span>
        ) : (
          <span className="text-red-400">&#10007;</span>
        ),
    },
    { title: "Recall@K", dataIndex: "recallAtK", key: "recallAtK", width: 100, render: (v: number) => `${(v * 100).toFixed(0)}%` },
    { title: "RR", dataIndex: "reciprocalRank", key: "reciprocalRank", width: 90, render: (v: number) => v.toFixed(4) },
    { title: "nDCG@K", dataIndex: "ndcgAtK", key: "ndcgAtK", width: 90, render: (v: number) => v.toFixed(4) },
  ];

  return (
    <>
      <Button type="default" icon={<ExperimentOutlined />} onClick={handleOpen} size="small">
        RAG 评估
      </Button>
      <Modal title="RAG 检索性能评估" open={open} onCancel={() => setOpen(false)} width={920} footer={null} destroyOnClose>
        <Tabs
          items={[
            {
              key: "eval",
              label: "评估",
              children: (
                <Space direction="vertical" className="w-full" size="middle">
                  {/* 参数 */}
                  <Card size="small" title="评估参数">
                    <Space>
                      <span className="text-sm">Top-K:</span>
                      <InputNumber min={1} max={20} value={topK} onChange={(v) => setTopK(v ?? 3)} />
                    </Space>
                  </Card>

                  {/* 测试用例 */}
                  <Card
                    size="small"
                    title={`测试用例 (${evalCases.length})`}
                    extra={
                      <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addCase}>
                        添加用例
                      </Button>
                    }
                  >
                    <Space direction="vertical" className="w-full">
                      {evalCases.map((ec, caseIdx) => (
                        <Card
                          key={caseIdx}
                          size="small"
                          type="inner"
                          extra={
                            evalCases.length > 1 && (
                              <DeleteOutlined className="text-red-400 cursor-pointer" onClick={() => removeCase(caseIdx)} />
                            )
                          }
                        >
                          <Space direction="vertical" className="w-full">
                            {/* 知识库 */}
                            <Space className="w-full">
                              <span className="text-xs w-20 shrink-0">知识库:</span>
                              <Select
                                showSearch
                                placeholder="选择知识库"
                                value={ec.kbId || undefined}
                                onChange={(v) => updateCase(caseIdx, "kbId", v)}
                                options={kbOptions}
                                style={{ width: 240 }}
                                allowClear
                                notFoundContent={<Empty description="暂无知识库" />}
                              />
                            </Space>
                            {/* 查询语句 */}
                            <Space className="w-full">
                              <span className="text-xs w-20 shrink-0">查询语句:</span>
                              <Input
                                placeholder="输入测试查询语句"
                                value={ec.query}
                                onChange={(e) => updateCase(caseIdx, "query", e.target.value)}
                                style={{ flex: 1 }}
                              />
                            </Space>
                            {/* 相关文档 ID — 逐行输入 */}
                            <Space className="w-full" align="start">
                              <span className="text-xs w-20 shrink-0 pt-1">相关文档ID:</span>
                              <div className="flex flex-wrap items-center gap-1.5 flex-1">
                                {ec.relevantDocIds.map((id, docIdx) => (
                                  <div key={docIdx} className="flex items-center gap-0.5">
                                    <Input
                                      size="small"
                                      placeholder={`文档 ${docIdx + 1}`}
                                      value={id}
                                      onChange={(e) => updateDocId(caseIdx, docIdx, e.target.value)}
                                      style={{ width: 160 }}
                                    />
                                    {ec.relevantDocIds.length > 1 && (
                                      <DeleteOutlined
                                        className="text-red-300 cursor-pointer text-xs"
                                        onClick={() => removeDocId(caseIdx, docIdx)}
                                      />
                                    )}
                                    {/* 加号始终在最后一项后面 */}
                                    {docIdx === ec.relevantDocIds.length - 1 && (
                                      <Button
                                        type="link"
                                        size="small"
                                        icon={<PlusOutlined />}
                                        onClick={() => addDocId(caseIdx)}
                                        className="p-0 text-blue-500"
                                      />
                                    )}
                                  </div>
                                ))}
                                {/* 如果列表为空，显示一个直接的加号按钮 */}
                                {ec.relevantDocIds.length === 0 && (
                                  <Button
                                    type="link"
                                    size="small"
                                    icon={<PlusOutlined />}
                                    onClick={() => addDocId(caseIdx)}
                                    className="p-0 text-blue-500"
                                  >
                                    添加文档ID
                                  </Button>
                                )}
                              </div>
                            </Space>
                          </Space>
                        </Card>
                      ))}
                    </Space>
                  </Card>

                  {/* 执行按钮 */}
                  <Button type="primary" icon={<ExperimentOutlined />} onClick={handleEvaluate} loading={loading} block size="large">
                    {loading ? "执行评估中..." : "执行评估"}
                  </Button>

                  {/* 结果 */}
                  {result && (
                    <>
                      <Card size="small" title="整体指标">
                        <Row gutter={16}>
                          {metricsList.map((m) => (
                            <Col span={6} key={m.label}>
                              <Card size="small" bordered={false}>
                                <Statistic title={m.label} value={m.value} precision={4} />
                              </Card>
                            </Col>
                          ))}
                        </Row>
                        <div className="text-xs text-gray-400 mt-2">
                          共 {result.totalCases} 个有效用例，Top-K = {result.topK}
                        </div>
                      </Card>
                      {result.caseResults.length > 0 && (
                        <Card size="small" title="各用例明细">
                          <Table
                            columns={caseResultColumns}
                            dataSource={result.caseResults.map((cr, i) => ({ ...cr, key: i }))}
                            pagination={false}
                            size="small"
                          />
                        </Card>
                      )}
                    </>
                  )}
                </Space>
              ),
            },
            {
              key: "history",
              label: (
                <span>
                  <HistoryOutlined className="mr-1" />
                  历史记录
                </span>
              ),
              children: history.length === 0 ? (
                <Empty description="暂无评估历史" />
              ) : (
                <Space direction="vertical" className="w-full">
                  {history.map((entry) => (
                    <Card
                      key={entry.id}
                      size="small"
                      hoverable
                      onClick={() => viewHistoryEntry(entry)}
                    >
                      <div className="flex items-center justify-between">
                        <Space>
                          <Text strong>{entry.timestamp}</Text>
                          <Tag>{entry.topK}</Tag>
                          <Text type="secondary">{entry.cases.length} 个用例</Text>
                        </Space>
                        <Space size="small">
                          <Text type="secondary" className="text-xs">
                            Hit@{entry.topK}: {(entry.result.metrics.hitRateAtK * 100).toFixed(0)}%
                          </Text>
                          <Text type="secondary" className="text-xs">
                            MRR: {entry.result.metrics.mrrAtK.toFixed(4)}
                          </Text>
                        </Space>
                      </div>
                    </Card>
                  ))}
                </Space>
              ),
            },
          ]}
        />
      </Modal>
    </>
  );
};

export default RagEvaluationModal;
