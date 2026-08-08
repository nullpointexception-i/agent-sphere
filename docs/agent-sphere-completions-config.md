# AgentSphere completions config 配置说明

> 位置：completions 管理页 → 编辑 Completions → `Config (JSON)` 字段（即 `agent_completions.config`）
> 作用：该 JSON 直接映射到模型请求参数，控制单次 LLM 调用的采样/输出/思考行为。

---

## 一、支持的参数

| 参数 | 类型 | 说明 | 示例 |
|---|---|---|---|
| `temperature` | number | 采样温度（0~2，越高越随机） | `0.1` |
| `max_tokens` | number | 最大输出 token 数 | `1024` |
| `top_p` | number | 核采样（0~1） | `0.9` |
| `presence_penalty` | number | 话题新鲜度惩罚（-2~2） | `0.3` |
| `frequency_penalty` | number | 重复惩罚（-2~2） | `0.3` |
| `stop` | string[] | 停止序列，命中即停止生成 | `["END"]` |
| `thinking` | boolean \| string | 思考开关：`false`=关闭思考、`true`=开启、`"disabled"`/`"enabled"` | `false` |
| `reasoning` | boolean \| string | 同 `thinking`（别名，二者任一即可） | `false` |

> 说明：
> - 未设置的参数不写入请求，保持模型默认值。
> - 未知键忽略。
> - `thinking`/`reasoning` 兼容布尔与字符串两种写法（Jackson 将布尔 `false`/`true` 强转为 `"false"`/`"true"`，内部再归一化为 `disabled`/`enabled`）。
> - `thinking` 是否被端点接受取决于模型/网关：部分 reasoner 模型可能忽略该参数。

---

## 二、全量配置示例

### 2.1 简历解析（resume_parse）
```json
{
  "temperature": 0.1,
  "max_tokens": 2048,
  "top_p": 0.9,
  "presence_penalty": 0.0,
  "frequency_penalty": 0.0,
  "thinking": false
}
```

### 2.2 5 维匹配（five_dim_match）
```json
{
  "temperature": 0.2,
  "max_tokens": 1024,
  "top_p": 0.9,
  "thinking": false
}
```

### 2.3 AI 触达话术（outreach）
```json
{
  "temperature": 0.7,
  "max_tokens": 512,
  "top_p": 0.95,
  "presence_penalty": 0.2,
  "frequency_penalty": 0.2,
  "thinking": true
}
```

### 2.4 自然语言搜索（nl_search）
```json
{
  "temperature": 0.1,
  "max_tokens": 1024,
  "thinking": false
}
```

### 2.5 组织收集（org_collect）
```json
{
  "temperature": 0.1,
  "max_tokens": 2048,
  "thinking": false
}
```

### 2.6 评估报告推荐理由（recommend_reason）
```json
{
  "temperature": 0.3,
  "max_tokens": 1024,
  "top_p": 0.9,
  "thinking": false
}
```

### 2.7 AI 面试题（interview_questions）
```json
{
  "temperature": 0.6,
  "max_tokens": 2048,
  "top_p": 0.95,
  "presence_penalty": 0.1,
  "frequency_penalty": 0.1,
  "thinking": false,
  "stop": ["\n\n"]
}
```

---

## 三、示例：关闭思考以加速响应

只需在 Config 中显式设置 `thinking: false`（或 `reasoning: false`）：

```json
{ "temperature": 0.1, "thinking": false }
```

请求将携带 `"thinking":{"type":"disabled"}`，模型跳过思考阶段，响应更快。

---

## 四、相关实现

- 配置读取：`CompletionsServiceImpl.applyConfig`（解析 `agent_completions.config` → `CompletionsConfigDTO` → `ChatCompletionRequestDTO`）
- 请求 DTO：`ChatCompletionRequestDTO`（`temperature`/`max_tokens`/`top_p`/`presence_penalty`/`frequency_penalty`/`stop`/`thinking`）
- 配置 DTO：`CompletionsConfigDTO`
- 管理端入口：completions 管理页 Config 字段（label 带 `?` 悬浮提示，展示支持范围与样例）
