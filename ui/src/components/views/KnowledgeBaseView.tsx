import React, { useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import {
  Card,
  Typography,
  Button,
  Upload,
  Table,
  Popconfirm,
  Space,
  message,
  Empty,
} from "antd";
import {
  BookOutlined,
  UploadOutlined,
  DeleteOutlined,
  FileOutlined,
} from "@ant-design/icons";
import type { UploadProps } from "antd";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";
import { useDocuments } from "../../hooks/useDocuments.ts";
import {
  uploadDocument,
  getDocumentStatus,
  type DocumentVO,
} from "../../api/api.ts";

const { Title, Text, Paragraph } = Typography;
const SUPPORTED_EXTENSIONS = [".md", ".markdown", ".txt", ".pdf"];

const KnowledgeBaseView: React.FC = () => {
  const { knowledgeBaseId } = useParams<{ knowledgeBaseId?: string }>();
  const { knowledgeBases } = useKnowledgeBases();
  const { documents, loading, refreshDocuments, deleteDocument } =
    useDocuments(knowledgeBaseId);

  const [uploading, setUploading] = useState(false);
  const pollingRef = useRef<number | null>(null);

  const stopPolling = () => {
    if (pollingRef.current !== null) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  };

  // 查找当前知识库的详细信息
  const currentKnowledgeBase = useMemo(() => {
    if (!knowledgeBaseId) return null;
    return (
      knowledgeBases.find((kb) => kb.knowledgeBaseId === knowledgeBaseId) ||
      null
    );
  }, [knowledgeBaseId, knowledgeBases]);

  // 处理文件上传
  const handleUpload: UploadProps["customRequest"] = async (options) => {
    const { file, onSuccess, onError } = options;
    const uploadFile = file as File;

    if (!knowledgeBaseId) {
      message.error("请先选择知识库");
      return;
    }
    const filename = uploadFile.name?.toLowerCase() || "";
    const isSupported = SUPPORTED_EXTENSIONS.some((ext) =>
      filename.endsWith(ext),
    );
    if (!isSupported) {
      const err = new Error("仅支持 .md / .markdown / .txt / .pdf 格式");
      message.error(err.message);
      onError?.(err);
      return;
    }

    setUploading(true);

    try {
      const resp = await uploadDocument(knowledgeBaseId, uploadFile);

      if (resp.processing) {
        message.info("文档上传成功，正在异步解析中...", 3);
        pollingRef.current = window.setInterval(async () => {
          try {
            const statusResp = await getDocumentStatus(resp.documentId);
            if (statusResp.status === "COMPLETED") {
              stopPolling();
              const durationSec =
                statusResp.duration != null
                  ? (statusResp.duration / 1000).toFixed(2)
                  : "?";
              message.success(
                `文档异步解析完成，解析用时 ${durationSec} 秒`,
              );
              console.log(`文档解析用时: ${statusResp.duration} ms`);
              await refreshDocuments();
              setUploading(false);
              onSuccess?.(file);
            } else if (statusResp.status === "FAILED") {
              stopPolling();
              message.error(
                statusResp.errorMessage || "文档异步解析失败",
              );
              await refreshDocuments();
              setUploading(false);
              onError?.(new Error(statusResp.errorMessage || "解析失败"));
            }
          } catch {
            // 轮询失败静默处理
          }
        }, 2000);
      } else {
        const durationSec = (resp.duration / 1000).toFixed(2);
        message.success(`文档上传并解析成功，解析用时 ${durationSec} 秒`);
        console.log(`文档解析用时: ${resp.duration} ms`);
        await refreshDocuments();
        setUploading(false);
        onSuccess?.(file);
      }
    } catch (error) {
      stopPolling();
      message.error(error instanceof Error ? error.message : "上传失败");
      onError?.(error as Error);
      setUploading(false);
    }
  };

  // 格式化文件大小
  const formatFileSize = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + " " + sizes[i];
  };

  // 表格列定义
  const columns = [
    {
      title: "文件名",
      dataIndex: "filename",
      key: "filename",
      render: (text: string) => (
        <Space>
          <FileOutlined />
          <span>{text}</span>
        </Space>
      ),
    },
    {
      title: "类型",
      dataIndex: "filetype",
      key: "filetype",
      width: 120,
    },
    {
      title: "大小",
      dataIndex: "size",
      key: "size",
      width: 120,
      render: (size: number) => formatFileSize(size),
    },
    {
      title: "操作",
      key: "action",
      width: 100,
      render: (_: unknown, record: DocumentVO) => (
        <Popconfirm
          title="确定要删除这个文档吗？"
          description="删除后将无法恢复"
          onConfirm={() => deleteDocument(record.id)}
          okText="确定"
          cancelText="取消"
        >
          <Button type="text" danger icon={<DeleteOutlined />} size="small">
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  // 未选择知识库时的提示
  if (!knowledgeBaseId) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          image={<BookOutlined className="text-6xl text-gray-300" />}
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                未选择知识库
              </Title>
              <Text type="secondary" className="text-sm">
                请从左侧知识库列表中选择一个知识库查看详情
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  // 知识库不存在
  if (!currentKnowledgeBase) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                知识库不存在
              </Title>
              <Text type="secondary" className="text-sm">
                请检查知识库 ID 是否正确
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  // 显示知识库详情和文档列表
  return (
    <div className="flex flex-col h-full p-6 overflow-y-auto">
      <div className="max-w-6xl w-full mx-auto">
        <div className="mb-3">
          <Card>
            <div className="flex items-start gap-4">
              <div className="w-16 h-16 rounded-lg bg-gradient-to-br from-blue-200 to-purple-200 flex items-center justify-center text-3xl shrink-0">
                <BookOutlined />
              </div>
              <div className="flex-1">
                <Title level={3} className="mb-2">
                  {currentKnowledgeBase.name}
                </Title>
                {currentKnowledgeBase.description && (
                  <Paragraph className="text-gray-600 mb-0">
                    {currentKnowledgeBase.description}
                  </Paragraph>
                )}
                <Text type="secondary" className="text-sm">
                  知识库 ID: {currentKnowledgeBase.knowledgeBaseId}
                </Text>
              </div>
            </div>
          </Card>
        </div>
        {/* 知识库信息卡片 */}

        <div className="mb-3">
          {/* 上传文档区域 */}
          <Card title="上传文档">
            <Upload
              customRequest={handleUpload}
              showUploadList={false}
              accept=".md,.markdown,.txt,.pdf"
              disabled={uploading}
            >
              <Button
                type="primary"
                icon={<UploadOutlined />}
                loading={uploading}
                size="large"
              >
                选择文件上传
              </Button>
            </Upload>
            <Text type="secondary" className="block mt-2 text-xs">
              支持格式: MD / TXT / PDF（文件 ≥ 20MB 将异步解析，解析完成后自动提示）
            </Text>
          </Card>
        </div>

        <div className="mb-3">
          {/* 文档列表 */}
          <Card title={`文档列表 (${documents.length})`}>
            {loading ? (
              <div className="text-center py-8">
                <Text type="secondary">加载中...</Text>
              </div>
            ) : documents.length === 0 ? (
              <Empty
                description={<Text type="secondary">暂无文档，请上传文档</Text>}
              />
            ) : (
              <Table
                columns={columns}
                dataSource={documents}
                rowKey="id"
                pagination={{
                  pageSize: 10,
                  // showSizeChanger: true,
                  showTotal: (total) => `共 ${total} 条`,
                }}
              />
            )}
          </Card>
        </div>
      </div>
    </div>
  );
};

export default KnowledgeBaseView;
