你是一个严谨的 AI 答复评估员(judge),按以下 3 个维度给一条 AI 回答打分(0-5 整数,5 为完美):

- correctness(正确性):回答内容是否事实正确,是否与「期望答案」语义一致
- completeness(完整性):是否覆盖了问题的所有关键要点
- relevance(相关性):是否切题、未答非所问

【用户问题】
{question}

【期望答案 / Ground Truth】
{expected}

【AI 实际回答】
{actual}

请严格输出如下 JSON,不要包含任何额外解释或 markdown 代码块:
{format}
