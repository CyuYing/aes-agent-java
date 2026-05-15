# Java 作业智能批改系统

基于 **LangChain4j + Spring Boot + RAG** 的智能化 Java 作业批改系统。支持上传 Word 作业文档，自动提取多道编程题的题目要求与学生代码，结合知识库中的评分标准，利用大语言模型进行逐题批改与综合评分。

---

## 一、系统简介

本系统面向 Java 程序设计课程的教学辅助场景，帮助教师快速批改学生提交的作业文档。系统通过 RAG（检索增强生成）技术，自动从知识库中检索对应的评分标准，结合大语言模型对每道编程题进行多维度评分。

### 核心能力

- **Word 作业文档解析** — 自动从 `.docx` 中提取多道编程题（题目要求 + 学生代码）
- **题意符合性检查** — 判断代码是否满足题目要求，功能是否完整
- **RAG 增强评分** — 自动检索评分标准和参考范例，评分有据可依
- **多维度评价** — 是否符合题意（30）+ 代码规范（20）+ 逻辑正确性（20）+ 性能效率（15）+ 可维护性（15），百分制
- **流式输出 (SSE)** — 实时显示每道题的批改过程
- **透明可解释** — 展示检索到的原始文档片段，评分依据一目了然

### 技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.5.3 | Java 17+ |
| LLM SDK | LangChain4j 0.36.2 | OpenAI 兼容接口 |
| LLM | DeepSeek Chat | 通过 `langchain4j-open-ai` 调用 |
| Embedding | BGE-small-zh | 本地 ONNX 运行，中文优化，首次约下载 100MB |
| 向量库 | Chroma / InMemory | 优先 Chroma，不可用时自动降级 |
| 文档解析 | PDFBox + Tika | 支持 PDF、DOCX、TXT |
| 前端 | 纯 HTML/CSS/JS | SSE + Fetch API，零依赖 |

---

## 二、快速预览

![截图：系统首页整体界面，展示左侧边栏（知识库管理、评估配置）和右侧主区域（单代码评估/作业文档批改双模式 Tab）]

![截图：作业批改结果页，展示总分卡片和逐题展开的评分详情]

---

## 三、使用说明

### 3.1 系统首页

启动应用后，浏览器访问 `http://localhost:8080`，进入系统首页。

页面分为左右两栏：
- **左侧边栏**：知识库管理、评估类型选择、检索上下文显示开关
- **右侧主区域**：两个功能 Tab ——「单代码评估」和「作业文档批改」

![截图：系统首页加载后的默认界面，左侧显示知识库为空提示，右侧显示单代码评估 Tab]

---

### 3.2 知识库管理

知识库用于存放 Java 作业的评分标准、编码规范、参考范例等文档。批改时会自动从中检索相关标准进行对照评分。

#### 步骤 1：放入评分标准文档

将 Java 作业评分标准文档（支持 `.pdf`、`.docx`、`.txt`）复制到项目目录的 `data/knowledge_base/` 文件夹下。

建议按以下格式命名文件名，系统会自动提取元数据：

```
Java编码规范_代码风格_Java17.docx
Java作业评分标准_构造方法重载_Java17.txt
```

#### 步骤 2：同步知识库

点击左侧边栏的 **「🔄 同步知识库」** 按钮，系统会自动加载文档、分块、向量化并建立索引。

![截图：知识库为空状态，显示提示"知识库为空。将评估标准文档放入 data/knowledge_base/ 后点击同步"]

同步完成后，左侧会显示已索引的片段数和文件列表：

![截图：知识库同步完成状态，显示"已索引: 42 个片段，文件数: 3"，下方列出文件列表]

> **提示**：如需更新评分标准，直接替换或新增 `data/knowledge_base/` 中的文件，然后重新点击同步即可。

---

### 3.3 单代码评估

适用于快速评估一段独立的 Java 代码，无需上传文档。

#### 操作步骤

1. 切换到 **「单代码评估」** Tab
2. 在文本框中粘贴要评估的 Java 代码
3. （可选）在左侧选择评估类型（通用评估、代码规范、算法与数据结构等）
4. 点击 **「🔍 开始评估」** 按钮

![截图：单代码评估界面，textarea 中粘贴了 Java 代码，评估类型选择为"通用评估"，下方显示"开始评估"按钮]

#### 查看结果

系统会流式显示 AI 的评估过程，完成后展示结构化评分结果：

- **总分卡片**：显示综合评分（满分 100）
- **分维度评分卡片**：代码规范、逻辑正确性、性能与效率、可维护性，各维度带进度条
- **详细评语**：Markdown 格式，包含总体评价、逐条问题、改进建议

![截图：单代码评估的评分结果，顶部显示总分卡片"综合评分 88/100"，下方四个维度评分卡片，再下方是 Markdown 格式的详细评语]

勾选左侧 **「显示检索上下文」**，可在评语下方查看系统从知识库中检索到的评分标准原文：

![截图：单代码评估结果底部展开的 RAG 来源区域，显示"📋 评估标准 | Java编码规范.docx"的片段内容]

---

### 3.4 作业文档批改

适用于批量批改学生提交的 Word 作业文档（含多道编程题）。

#### 操作步骤

1. 切换到 **「作业文档批改」** Tab
2. 点击或拖拽上传学生的 `.docx` 作业文档

![截图：作业文档批改界面，显示拖拽上传区域，提示"点击或拖拽上传 Word 文档，支持 .docx 格式"]

3. （可选）在左侧选择评估类型
4. 点击 **「🔍 开始批改」** 按钮

系统会自动解析文档、提取题目和代码，逐题调用 AI 进行批改：

![截图：作业文档批改进行中，显示转圈动画和文字"正在解析文档并逐题批改..."]

> **支持的文档格式**：系统通过正则识别 `【第2题】`、`第1题`、`1.`、`一、` 等常见题号格式切分题目。如果文档中没有明确题号，会将整个文档作为一道题进行批改。

---

### 3.5 查看批改结果

批改完成后，页面会展示：

#### 总分汇总

顶部显示作业总分卡片，包含文件名称和总分：

![截图：作业批改结果顶部，显示总分卡片"作业总分 JV-作业样本.docx  95/100"]

#### 逐题详情

每道题以卡片形式展示，默认折叠。点击卡片可展开查看：

- **题号与标题**：如 `【第2题】`
- **各维度得分**：是否符合题意、代码规范、逻辑正确性、性能与效率、可维护性
- **详细评语**：Markdown 格式，包含总体评价、逐条问题清单、改进建议、代码片段对比

![截图：作业批改结果的逐题卡片，显示第1题折叠状态和得分，以及展开后的维度进度条和详细评语]

---

## 四、部署指南

### 4.1 环境准备

#### JDK 17+

```bash
# 验证安装
java -version
# 应显示 17 或更高版本，例如：openjdk version "17.0.8"
```

如未安装，推荐下载 [Eclipse Temurin](https://adoptium.net/) 或 [Oracle JDK](https://www.oracle.com/java/technologies/downloads/)。

#### Maven 3.8+

```bash
# 验证安装
mvn -v
# 应显示 Apache Maven 3.8.x 及以上
```

如未安装：
- **Windows**：下载 [Maven 二进制包](https://maven.apache.org/download.cgi)，解压后配置 `MAVEN_HOME` 和 `PATH`
- **Linux/macOS**：`sudo apt install maven` 或 `brew install maven`

#### （可选）Python 3.10+ 与 Chroma

Chroma 向量库可提升检索性能和持久化能力。如不需要，系统会自动回退到内存向量库。

```bash
# 安装 Chroma
pip install chromadb

# 验证安装
chroma --version
```

---

### 4.2 获取代码

```bash
# 克隆仓库（或下载源码压缩包解压）
git clone <仓库地址> aes-agent-java
cd aes-agent-java
```

---

### 4.3 配置修改

编辑 `src/main/resources/application.properties`：

```properties
# ==========================================
# 必填：DeepSeek API Key
# ==========================================
# 方式一：直接写入（仅本地测试，生产环境建议用环境变量）
deepseek.api.key=sk-your-api-key-here

# 方式二：使用环境变量（推荐）
# deepseek.api.key=${DEEPSEEK_API_KEY}

# ==========================================
# 可选：DeepSeek 基础配置
# ==========================================
deepseek.base.url=https://api.deepseek.com
deepseek.model.name=deepseek-chat

# ==========================================
# 可选：Chroma 向量库
# ==========================================
chroma.base.url=http://localhost:8000
chroma.collection.name=aes-knowledge

# ==========================================
# 可选：知识库路径
# ==========================================
aes.knowledge-base.path=data/knowledge_base

# ==========================================
# 可选：服务端口
# ==========================================
server.port=8080
```

**获取 DeepSeek API Key：**
1. 访问 [DeepSeek 开放平台](https://platform.deepseek.com/)
2. 注册/登录后进入「API Keys」页面
3. 创建新 Key 并复制

---

### 4.4 构建打包

```bash
# 清理并编译
mvn clean compile

# 打包为可执行 JAR（生产部署使用）
mvn clean package -DskipTests
```

打包成功后，会在 `target/` 目录下生成：
```
target/
├── aes-agent-1.0.0.jar          # 可执行 JAR
└── ...
```

---

### 4.5 启动应用

#### 开发模式（热重载，适合调试）

```bash
mvn spring-boot:run
```

#### 生产模式（使用打包后的 JAR）

```bash
# 设置环境变量后启动（Linux/macOS）
export DEEPSEEK_API_KEY=sk-your-api-key
java -jar target/aes-agent-1.0.0.jar

# Windows PowerShell
$env:DEEPSEEK_API_KEY="sk-your-api-key"
java -jar target/aes-agent-1.0.0.jar

# Windows CMD
set DEEPSEEK_API_KEY=sk-your-api-key
java -jar target/aes-agent-1.0.0.jar
```

#### 验证启动

```bash
# 检查服务是否运行
curl http://localhost:8080/api/knowledge/stats
```

正常应返回：
```json
{"chunkCount":0,"fileCount":0,"files":[],"metadata":[]}
```

**首次启动注意：** 会自动下载 BGE-small-zh 嵌入模型（约 100MB），需等待 1-2 分钟。看到 `Tomcat started on port 8080` 即表示启动成功。

访问前端：`http://localhost:8080`

---

### 4.6 （可选）启动 Chroma 向量库

在另一个终端中执行：

```bash
# 进入项目目录
chroma run --path ./chroma-data
```

Chroma 运行在 `http://localhost:8000`。应用启动时会自动尝试连接，成功日志示例：
```
Chroma 向量库连接成功, collection: aes-knowledge
```

若连接失败，会自动降级为内存向量库，不影响核心功能：
```
Chroma 连接失败, 回退到 InMemoryEmbeddingStore
```

---

### 4.7 初始化知识库

1. **准备评分标准文档**
   
   将 Java 作业评分标准、参考范例等文档放入 `data/knowledge_base/` 目录。支持格式：`.pdf`、`.docx`、`.txt`。
   
   建议命名格式（系统自动提取元数据）：
   ```
   Java编码规范_代码风格_Java17.docx
   Java作业评分标准_构造方法重载_Java17.txt
   ```

2. **同步知识库**

   方式一：前端页面左侧点击 **「🔄 同步知识库」**
   
   方式二：调用 API：
   ```bash
   curl -X POST http://localhost:8080/api/knowledge/sync
   ```

3. **验证知识库**

   ```bash
   curl http://localhost:8080/api/knowledge/stats
   ```
   
   正常应返回非零的 `chunkCount` 和 `fileCount`：
   ```json
   {"chunkCount":42,"fileCount":3,"files":["..."],"metadata":[...]}
   ```

---

### 4.8 功能验证

#### 单代码评估测试

打开 `http://localhost:8080`，切换到「单代码评估」Tab，粘贴 Java 代码，点击「开始评估」。

或调用 API：
```bash
curl -X POST http://localhost:8080/api/score \
  -H "Content-Type: application/json" \
  -d '{"content":"public class Hello { public static void main(String[] args) { System.out.println(\"Hello\"); } }","category":"general"}'
```

#### 作业文档批改测试

切换到「作业文档批改」Tab，上传 `.docx` 作业文档，点击「开始批改」。

或调用 API：
```bash
curl -X POST http://localhost:8080/api/homework/grade \
  -F "file=@作业样本.docx" \
  -F "category=general"
```

---

## 五、API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/` | 前端页面 |
| `GET` | `/api/knowledge/stats` | 知识库状态 |
| `POST` | `/api/knowledge/sync` | 重建知识库索引 |
| `POST` | `/api/score` | 单代码同步评分 |
| `GET/POST` | `/api/score/stream` | 单代码流式评分 SSE |
| `POST` | `/api/homework/grade` | 作业文档同步批改（multipart） |
| `POST` | `/api/homework/grade/stream` | 作业文档流式批改 SSE（逐题推送） |

### 请求示例

```bash
# 单代码评分
curl -X POST http://localhost:8080/api/score \
  -H "Content-Type: application/json" \
  -d '{"content":"public class Hello { ... }","category":"general"}'

# 作业文档批改
curl -X POST http://localhost:8080/api/homework/grade \
  -F "file=@作业.docx" \
  -F "category=algorithm"
```

---

## 六、知识库管理

### 文件命名规范

文件名自动提取元数据，建议按以下格式命名：

```
{类型}_{主题}_{Java版本}.pdf
```

示例：`Java编码规范_代码风格_Java17.docx`

自动提取的元数据字段：

| 字段 | 说明 | 示例值 |
|------|------|------|
| `type` | 文档类型 | `standard`（评分标准）、`reference`（参考范例） |
| `category` | 技术类别 | `code-style`、`algorithm`、`design`、`performance`、`testing`、`security` |
| `java` | Java 版本 | `8`、`11`、`17`、`21` |

---

## 七、常见问题排查

### 启动报错：端口 8080 被占用

```bash
# 查找占用进程
# Windows
netstat -ano | findstr :8080
taskkill /PID <进程号> /F

# Linux/macOS
lsof -i :8080
kill -9 <PID>
```

或在 `application.properties` 中修改端口：`server.port=8081`

### API 调用返回 500 / AI 评分无输出

- 检查 `application.properties` 中的 `deepseek.api.key` 是否已正确配置
- 确认 API Key 是否有效（DeepSeek 平台余额是否充足）
- 查看应用控制台日志中的具体错误信息

### Chroma 连接失败

- 确认 Chroma 是否已启动：`curl http://localhost:8000/api/v1/heartbeat`
- 检查 `application.properties` 中的 `chroma.base.url` 是否正确
- 如不需要 Chroma，可忽略此警告，系统会自动使用内存向量库

### 知识库同步后 chunkCount 仍为 0

- 确认 `data/knowledge_base/` 目录下存在文件
- 确认文件格式为 `.pdf`、`.docx` 或 `.txt`
- 查看应用日志是否有加载失败的错误提示

### 作业文档解析失败 / 未识别到题目

- 确认上传的是 `.docx` 格式（`.doc` 旧格式兼容性较差）
- 确认文档中包含题号标记（如 `【第1题】`、`第1题`、`1.` 等）
- 如文档无明确题号，系统会将整个文档视为一道题进行批改

---

## 八、架构流程

```
用户上传 Word 作业文档
    │
    ▼
DocumentParserService.parseDocx()
    │  ├─ Tika 提取全文
    │  └─ 正则切分 → List<QuestionEntry>（题目 + 代码）
    │
    ▼
HomeworkService.gradeHomework()
    │  ├─ 对每道题：
    │  │   ├─ KnowledgeService.retrieve() → RAG 检索评分标准
    │  │   ├─ 构建 Prompt（题目要求 + 学生代码 + RAG 上下文）
    │  │   └─ DeepSeek Chat API → JSON 结构化评分结果
    │  └─ 汇总所有题目得分 → HomeworkResult
    │
    ▼
前端展示：总分卡片 + 逐题展开详情 + RAG 来源
```

---

## 九、项目目录树

```
aes-agent-java/
├── pom.xml                                    # Maven 项目配置
├── README.md
├── data/
│   └── knowledge_base/                        # 知识库文档（评分标准 / 参考范例）
└── src/
    └── main/
        ├── java/com/aes/
        │   ├── AesAgentApplication.java       # Spring Boot 入口
        │   ├── config/
        │   │   └── AesConfig.java             # Bean 配置（LLM / Embedding / 向量库）
        │   ├── controller/
        │   │   └── AesController.java         # REST API（评分 + 作业批改 + 知识库）
        │   ├── model/
        │   │   └── Dto.java                   # 所有 DTO / Record
        │   └── service/
        │       ├── KnowledgeService.java      # 知识库管理（加载/分块/向量化/检索）
        │       ├── ScoringService.java        # 单代码评分引擎（含 Prompt）
        │       ├── DocumentParserService.java # Word 文档解析（提取题目+代码）
        │       └── HomeworkService.java       # 作业批改工作流（解析→RAG→LLM→汇总）
        └── resources/
            ├── application.properties          # 应用配置
            └── static/
                └── index.html                  # 前端 UI（单页，双模式）
```

---

## 十、注意事项

- 首次启动会下载 BGE-small-zh 模型（约 100MB），需等待 1-2 分钟
- 知识库数据位于项目 `data/knowledge_base/` 目录，放入评分标准文档后需点击同步
- 当前作业批改基于 LLM 静态分析，不实际编译运行学生代码；教学辅助场景下建议配合人工复核
- AI 评分结果仅供教学参考，不作为正式考试成绩
