以下步骤从零开始搭建完整的 AgentSphere 开发环境。

### 1.1 环境准备

| 依赖 | 版本要求 | 用途 |
|------|---------|------|
| **Java** | 21+（推荐 Eclipse Temurin 21） | 后端运行环境 |
| **Maven** | 3.9+ | 后端构建工具 |
| **Node.js** | ≥20 | 前端构建运行 |
| **npm** | 10+（随 Node 自带） | 前端包管理 |
| **Docker & Docker Compose** | 最新稳定版 | 启动 Postgres + Redis 中间件 |
| **Chrome 浏览器** | 最新稳定版 | 加载扩展、执行浏览器操作 |
| **LLM API Key** | — | DeepSeek / OpenAI / GLM 等模型供应商 |

### 1.2 克隆仓库

```bash
git clone https://github.com/nullpointexception-i/agent-sphere
cd agent-sphere
```

项目包含三个子模块：
- `agent-sphere/` — 后端（Java 21, Spring Boot, Maven multi-module）
- `agent-sphere-ui/` — 前端（React 19, UmiJS Max, Ant Design Pro）
- `agent-sphere-chrome-extension/` — Chrome 扩展（Manifest V3）

### 1.3 启动中间件（Postgres + Redis）

```bash
cd agent-sphere/agent-docker-middleware
docker compose up -d
```

> **macOS 注意：** `docker-compose.yml` 中 volume 路径硬编码为 macOS 路径（`/Users/.../Desktop/...`）。如果启动报错，删除 compose 中的 `volumes` 配置，或替换为本机路径。

启动3个服务：

- PostgreSQL 14（`localhost:5432`），数据库名 `buukle_agent_2026061101`，用户 `buukle:buukle123`
- postadmin（`localhost:5050`）, postgre数据库的web客户端,便于查询本地数据
- Redis 7（`localhost:6379`）

![middleware-docker.png](agent-sphere-readme/middleware-docker.png)

![middleware-postadmin-web.png](agent-sphere-readme/middleware-postadmin-web.png)

验证连接：
```bash
docker compose ps
# 确保 postgres 和 redis 状态均为 Up
```

### 1.4 启动后端

后端使用 Maven 多模块项目，首次需要全量编译：

```bash
cd agent-sphere

# 首次：全量编译并安装依赖（跳过测试加速）
mvn install -DskipTests -q

# 启动 Spring Boot
mvn -pl agent-sphere-bootstrap spring-boot:run -am
```

后端将在 `http://localhost:8080` 启动，API 前缀 `/api/v1/...`。

**环境变量覆盖（可选）：**

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DB_HOST` | `127.0.0.1` | PostgreSQL 主机 |
| `DB_PORT` | `5432` | PostgreSQL 端口 |
| `DB_USERNAME` | `buukle` | 数据库用户 |
| `DB_PASSWORD` | `buukle123` | 数据库密码 |
| `REDIS_HOST` | `127.0.0.1` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |

示例：
```bash
DB_HOST=192.168.1.100 mvn -pl agent-sphere-bootstrap spring-boot:run -am
```

启动后 Flyway 会自动执行数据库迁移。观察日志确认无报错：
```
... Flyway: Successfully applied 1 migration ...
... Started Application in X.XXX seconds ...
```

### 1.5 启动前端

```bash
cd agent-sphere-ui
npm install     # 首次需要（项目使用 legacy-peer-deps）
npm run dev
```

前端开发服务器将在 `http://localhost:8000` 启动。`npm run dev` 自动将 `/api/` 请求代理到 `http://localhost:8080`（配置见 `config/proxy.ts`）。

> SSE（`/stream`）代理有特殊配置：剥离 `Accept-Encoding` 并设置 `Cache-Control: no-transform`，确保流式推送正常。修改 proxy 时请保留此逻辑。

### 1.6 注册与登录

1. 浏览器打开 `http://localhost:8000/user/register`
2. 输入用户名、密码、显示名，点击注册
3. 注册成功后跳转到登录页，用相同凭证登录
   ![ui-login.png](agent-sphere-readme/ui-login.png)
4. 登录成功进入 Dashboard
   ![ui-dashboard.png](agent-sphere-readme/ui-dashboard.png)



> 注册接口 `POST /api/v1/auth/register`，仅需用户名 + 密码，无邮箱验证环节。

### 1.7 加载 Chrome 扩展

1. Chrome 地址栏输入 `chrome://extensions`
2. 右上角开启 **开发者模式**
3. 点击 **加载已解压的扩展**
4. 选择项目中的 `agent-sphere-chrome-extension/` 目录
   ![ui-extension.png](agent-sphere-readme/ui-extension.png)
5. 扩展图标出现在工具栏，点击图标 → **Settings** Tab
6. 填写：
    - **Backend URL:** `http://localhost:8080`
    - **Frontend URL:** `http://localhost:8000`
7. 点击 **保存**
   ![ui-extension-pop.png](agent-sphere-readme/ui-extension-pop.png)

> 每次更新扩展代码后，在 `chrome://extensions` 页面点击扩展卡片上的刷新按钮重新加载。

![ui-extension-refresh.png](agent-sphere-readme/ui-extension-refresh.png)

### 1.8 配置模型供应商

Agent 对话依赖 LLM，需要先配置模型供应商和路由。

1. 在 UI 中进入 **模型供应商** 页面（`/models`）
2. 点击 **添加供应商**
   ![ui-model-provider.png](agent-sphere-readme/ui-model-provider.png)
3. 创建 **模型路由**：
    - 为刚添加的供应商创建一条主路由
    - 设置 `maxInputTokens`（影响后续压缩预算计算）
    - 可选：添加一条 fallback 路由，主路由超时后自动切换
4. 保存路由配置
   ![ui-model-route.png](agent-sphere-readme/ui-model-route.png)

> `maxInputTokens` 很重要：压缩预算 = `maxInputTokens × budget-ratio`（默认 0.7），超过此预算触发上下文压缩。

5. 创建 **api-key**:
    - 为刚添加的供应商创建n个api-key
    - 通过单选框,指定使用哪个key
   
  ![ui-model-api-key.png](agent-sphere-readme/ui-model-api-key.png)


### 1.9 配置 Agent 实例

1. 进入 **实例管理** 页面（`/instances`）
2. 点击 **创建实例**
3. 保存实例
    - **名称**：实例标识
    - **System Prompt**：系统指令，定义 Agent 的角色和行为
4. 配置能力和模型
    - **模型路由**：选择上一步创建的路由
    - **能力绑定**：为该实例绑定可用能力（MCP / Builtin / CLI / Skill）

![ui-instance-capability.png](agent-sphere-readme/ui-instance-capability.png)
### 1.10 开始对话

1. 进入 **聊天** 页面（`/chat`）
2. 选择或新建会话（Session）
3. 在输入框输入消息，按 Enter 发送
4. 观察：
    - **打字机效果**：LLM 逐 token 输出
    - **推理面板**：展示 LLM 推理过程和工具调用状态
    - **工具调用**：浏览器操作、网页读取等实时回显
    - **SSE 推送**：浏览器 DevTools → Network → Filter `/stream`
   
![ui-chat.png](agent-sphere-readme/ui-chat.png)

#### 典型交互示例

```
用户：帮我查一下今天的新闻
Agent：→ [调用 WebFetch 工具] → 返回结果 → 整理并回复
```

```
用户：打开百度，搜索"广州天气"
Agent：→ [Chrome Extension 导航到 baidu.com] → [输入搜索词] → [截图返回] → 回复
```

> 表单自动填写是 Builtin 工具之一，Agent 可以自动识别表单字段并填入内容。

### 1.11 调试技巧

| 场景 | 方法 |
|------|------|
| 查看后端日志 | 后端终端实时输出，或 `tail -f agent-sphere/logs/*.log` |
| 查看前端日志 | 浏览器 DevTools → Console |
| 查看 SSE 通信 | 浏览器 DevTools → Network → Filter `/stream` |
| 检查插件状态 | 点击扩展图标，绿色"已连接"表示正常 |
| 数据库查询 | `docker exec -it agent-docker-middleware-postgres-1 psql -U buukle -d buukle_agent_2026061101` |
| Flyway 迁移检查 | `select * from flyway_schema_history;`（在 psql 中执行） |
| 重置数据库 | `docker compose down -v && docker compose up -d`（⚠️ 删除所有数据） |

### 1.12 特点

| 维度 | 方式 |
|------|------|
| **实时性** | 用户能实时看到浏览器被操作的过程 |
| **稳定性** | 工具执行超时后有兜底机制，不阻塞后续流程 |
| **安全性** | 所有操作经过用户本地 Chrome 执行，不经过云端 |
| **可扩展** | 工具 SPI 机制，可注册任意类型的能力 |
| **可观测** | 每次工具调用都有日志/事件记录，可回溯 |

### 1.13 SSO 身份源与外部接入

第三方业务系统可通过 **OIDC SSO** 让用户免密登录，并用 `code + subject + businessType` 直连 `/api/v1/api/*` 调用能力。

#### 1.13.1 启动本地 OIDC mock（可选）

```bash
node agent-sphere/local-dev/mock-oidc-server.mjs   # 监听 :9000
```

每次启动**随机生成一个模拟用户身份**（见启动日志）；可用 `MOCK_IDP_SUBJECT` / `MOCK_IDP_PREFERRED_USERNAME` / `MOCK_IDP_EMAIL` / `MOCK_IDP_NAME` 固定。

#### 1.13.2 新增身份源

打开「系统管理 → 身份源 → 新建」，填写 OIDC 端点（如 `issuer=http://localhost:9000`、`authorization_endpoint=/oauth2/authorize`、`token_endpoint=/oauth2/token`、`jwks_url=/jwks`），选择**默认角色**，可选填**资源模板** JSON，并启用。

用户**首次**登录时，后端开通本地用户（授予默认角色）并**异步**按模板生成其**私有资源副本**（含自动绑定内置浏览器工具的实例）。右上角显示 `provider@subject`（如 `bole@elvin`）。

#### 1.13.3 外部调用能力

```bash
# completions（单次 LLM 调用）
curl -X POST http://localhost:8080/api/v1/api/completions \
  -H 'Content-Type: application/json' \
  -d '{"code":"business","subject":"elvin","businessType":"resume_parse",
       "input":{"resumeText":"张三，6年经验..."}}'

# tasks（异步，可选回调）
curl -X POST http://localhost:8080/api/v1/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"code":"business","subject":"elvin","businessType":"sourcing",
       "goal":"整理候选人张三的公开画像","callbackUrl":"https://bole.example.com/cb"}'
curl "http://localhost:8080/api/v1/api/tasks/1?code=business&subject=elvin&businessType=sourcing"
```

完成的任务会把结构化输出落为**任务产物**，可在「产出 → 任务产物」页查看。完整 API 契约见主 README §5。

---
