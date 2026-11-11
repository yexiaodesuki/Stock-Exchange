<!-- 无重排序RAG代码级交付索引：说明输入输出、代次、降级和测试边界，不包含私密配置。 -->
# 无重排序 RAG 实现说明

## 1. 文档、切片与本机向量

原来 `AssistantKnowledgeService` 同时加载文件和关键词检索，现在只负责问答编排；原文职责拆到 `service/assistant/rag/KnowledgeDocumentService.java`。以下 Java 路径除注明外均位于 `trading-api/src/main/java/com/itranswarp/exchange/`。

`KnowledgeDocumentService` 的构造器只加载三个固定资源，`load` 校验文件大小和格式，`contentHash` 计算内容身份。`Section` 保存章节编号、文档名、标题、别名、原文、正文哈希版本；`Chunk` 保存片段编号、所属章节、原文和 token 数。都不是账户模型。`chunks` 校验父章节、顺序、token上限、UTF-16起止偏移及全文覆盖，不能用一个短片段冒充整章已索引。

`build/rag/embedding/model_loader.py` 固定模型和快照版本，先验证只读缓存，再按允许下载开关加载；`load_model` CPU预热后才可READY。`app.py` 的 `split_text` 使用真实 tokenizer 切片，`process` 区分查询/文档：

```python
values = [QUERY_INSTRUCTION + t if kind == "query" else t for t in texts]
if any(len(model.tokenizer(t, add_special_tokens=True)["input_ids"]) > 512 for t in values):
    raise ValueError("EMBEDDING_TOKEN_LIMIT")
vectors = model.encode(values, normalize_embeddings=True, batch_size=8, show_progress_bar=False).tolist()
```

这样文档与查询使用固定且可辨识的预处理；超长输入明确拒绝，不能让模型静默截断。`EmbeddingClient` 定义本机协议；`HttpEmbeddingClient` 检查完整模型指纹、512维、数值类型、有限值、归一化和批次完整性。`RagHttp` 统一超时、响应体上限、禁止重定向和本机端点校验。没有把Python推理塞进撮合线程。

## 2. 完整索引代次

`repository/ChromaKnowledgeRepository.java` 固定默认租户/数据库，只接受 `securities-knowledge-` 哈希集合。`collection` 显式配置cosine并核对服务返回配置，`upsert` 显式提交本机向量，`complete` 核对数量、片段ID、正文、代次；不能用count相等代替内容正确。它没有面向用户的管理或删除方法。

`KnowledgeIndexService.start` 使用独立单线程后台任务，每15秒检查；关键顺序为：

```java
if(!repository.complete(collection,chunks,generation)){
  for(int offset=0;offset<chunks.size();offset+=16){
    var batch=chunks.subList(offset,Math.min(offset+16,chunks.size()));
    var vectors=embedding.embed(batch.stream().map(Chunk::text).toList(),false,15000);
    repository.upsert(collection,batch,vectors,generation);
  }
}
if(!repository.complete(collection,chunks,generation))throw new IllegalStateException("INDEX_INCOMPLETE");
current=new Index(collection,generation,chunks);state="READY";reason="";
```

`current` 是volatile不可变代次引用，完整验证后一次发布。正文、模型指纹、切片算法和实际片段共同决定generation；重启复用相同完整集合，部分建库重新补齐，新正文不混查旧集合。失败只置知识状态UNAVAILABLE，异常正文不外泄；不改变引擎的READY，也不访问资金库。旧集合保留，暂不自动回收。

## 3. 检索、生成与失败分支

`KnowledgeRetrievalService.retrieve` 固定候选数8，按余弦距离0.46过滤，与最多4章关键词候选做RRF；同章去重，最多3片、900 token。距离是排序指标，不是正确概率；无候选时不生成。动态入金额度在 `documents.expand(c.text())` 时才填入公开证据，不存入向量索引。

`AssistantKnowledgeService.read` 的决定性分支：

```java
if(retrieval==null)return lexical(question,"RAG_DISABLED");
try{chunks=retrieval.retrieve(question);}
catch(InterruptedException interrupted){Thread.currentThread().interrupt();return lexical(question,"RAG_INTERRUPTED");}
catch(Exception unavailable){return lexical(question,"RAG_RETRIEVAL_UNAVAILABLE");}
if(chunks.isEmpty())return facts(List.of(),"RAG_NO_EVIDENCE","KNOWLEDGE_NOT_FOUND","");
```

关闭/故障退回当前JAR原文，而不是旧向量结果；无证据明确不知道。随后没有生成配置就直接返回证据；有配置则调用 `KnowledgeAnswerClient`，错误或超时返回证据并标识 `RAG_EXTRACTIVE_FALLBACK`。不会把降级显示为模型成功。

`HttpKnowledgeAnswerClient` 与分类器分离，复用本地聊天端点、模型和密钥配置；`request` 只包含问题、公开证据和固定只读指令。6.5秒/768token/32KiB有界调用；`validate` 检查完成原因、JSON结构、引用只属于本次K编号、文内编号一致、阿拉伯数字出自引用证据。它不获得用户ID、SQL、下单或入金工具；这些校验不等于证明语义完全正确，不能宣称零幻觉。

`ApiConfiguration` 新增文档、嵌入、向量库、索引、生成器Bean，并给索引配置init/destroy生命周期；`config-repo/securities-api.yml` 集中公开参数。密钥仍只在 `.env.securities`。`AssistantService.status` 加知识状态，知识响应使用RAG自己的mode/reason；原账户、订单、入金和行情分支不改记账逻辑。`AssistantPresentation` 仅在有生成文本时展示生成内容，否则展示原文。

## 4. 页面、环境和文件清单

| 文件 | 本次职责或修改点 |
| --- | --- |
| `build/docker-compose.yml` | 增加Chroma和embedding、两个命名卷、只读可选缓存挂载；只发布本机端口。 |
| `build/rag/embedding/Dockerfile`、`requirements.txt`、`.dockerignore` | 固定CPU服务基础镜像与直接依赖，排除权重和缓存；权重不进入Git或镜像。传递依赖尚未全量锁定。 |
| `build/rag/embedding/model_loader.py`、`app.py` | 固定版本加载、预热、分片、向量、并发和请求上限；浏览器Origin请求拒绝；不输出问题日志。 |
| `build/rag/embedding/test_app.py` | 假分词器/向量的3项无网络逻辑测试。 |
| `EmbeddingClient.java`、`HttpEmbeddingClient.java`、`RagHttp.java` | 受限协议、模型身份与有界HTTP。 |
| `KnowledgeDocumentService.java`、`KnowledgeIndexService.java`、`KnowledgeRetrievalService.java` | 原文白名单、完整代次、无重排序混合召回。 |
| `KnowledgeAnswerClient.java`、`HttpKnowledgeAnswerClient.java` | 独立知识生成接口和受证据约束的HTTPS实现。 |
| `repository/ChromaKnowledgeRepository.java` | Chroma v2集合、向量写入、完整验证、只读查询。 |
| `AssistantKnowledgeService.java`、`AssistantService.java`、`AssistantPresentation.java`、`config/ApiConfiguration.java` | 问答编排、模式、展示和Spring生命周期装配。 |
| `config-repo/securities-api.yml` | 公开地址、RAG开关、0.46距离、最多3片。 |
| `ui/.../service/ApiProxyService.java` | 仅助手解释请求代理超时延长到15秒；其他仍9秒。 |
| `ui/.../static/securities/assistant.mjs`、`index.html` | 区分生成/原文/降级，显示[K1]引用，说明外发范围；所有文本安全渲染。 |
| `start.ps1` | 核心四容器与七个 Java 应用先就绪，仅指定 `-Rag` 或 `-Full` 时才准备两个可降级 RAG 容器；内容指纹避免无变化重复构建。 |
| `build/securities/test-startup.ps1` | 原13组之外新增关闭、构建缓存、故障仅警告三组；通过注入动作验证，不启动容器。 |
| `build/.env.securities.example`、`.gitignore` | 公开可选配置及缓存忽略。实际私密配置仅补本机模型源路径，未改聊天凭据。 |
| `RagLogicTest.java`、`RagLiveTest.java` | 假协议/引用约束，真实本机向量索引生命周期与30题小样本；不调用付费生成。 |
| `AssistantPhaseTwoTest.java` | 版本断言从手写日期改为正文哈希。 |
| `ui/.../SecuritiesUiProcessTest.java` | 隔离交易子进程关闭RAG/生成，不使用正式集合或供应商。 |
| `ui/src/test/js/browser.mjs` | 假生成响应验证引用及HTML转义；与真实交易回归分清边界。 |
| `build/securities/test-phase4.ps1` | 探测本机服务后自动启用真实向量测试，离线明确跳过；恢复原环境变量。 |
| `README.md`、`docs/running.md`、`docs/assistant.md`、本文件 | 环境、模型、隐私、降级、代码与测试说明。 |

这里只列本次RAG改动；工作区已有行情摘要、知识初版和更早助手修复，不将其全部算作本次新增。

## 5. 测试意义与限制

`RagLogicTest` 覆盖切片缺口/重复文本、哈希和动态策略分离、原文回退、协议错误、有限向量、引用伪造和数字越界。`RagLiveTest` 用真实BGE/Chroma，模拟只存一片的中断索引，再验证补齐、重启不重复文档嵌入、新文档代次、删除章节不再查旧代、假生成超时降级和未知问题不触发生成。只清理登记的随机测试集合，不删除正式知识代次。

浏览器的生成卡片测试明确使用假响应，只证明前端协议、引用和文本安全；真实聊天供应商的结构化输出兼容性、耗时、中文生成忠实度和大并发尚需另行授权测试。完整交易回归使用随机库、隔离Topic/Redis资源，不通过正式账户充值来造测试数据。当前30题同时参与门槛校准，不是独立盲测集。

## 6. 本轮实际验收（2026-11-02）

- 完整 `pwsh -NoProfile -File build/securities/test-phase4.ps1 -Gateway`：Java 133项、132通过、1项条件跳过，零失败/错误；前端纯逻辑6项、Python纯逻辑3项通过。真实浏览器走临时Nginx，包含原交易、撤单、入金、重连、恢复，以及RAG假生成响应的引用/HTML转义。
- 最后新增严格JSON数值类型用例后，串行执行 `SECURITIES_RAG_LIVE_TESTS=true` 的 `mvn -f build/pom.xml -pl ../trading-api -am test -Dtest=Rag*Test -Dsurefire.failIfNoSpecifiedTests=false`：8项全通过。包含实际向量服务、随机Chroma集合和模拟嵌入故障后退回原文；生成仍是假模型。此前一次补测误与部署clean打包同时运行导致类缺失，结束打包后串行重跑通过，不是业务逻辑修复。
- `test-startup.ps1`：PowerShell 5.1及7各16组通过；嵌入服务最新镜像中Python3项再次通过，实际携带Origin的POST返回403。
- 正式启动七个Java全部就绪，八个容器健康；签名只读status返回 `knowledge.state=READY`、10片、`reranking=false`。没有调用正式explain接口，避免触发真实聊天费用。
- 正式关键表部署前后SHA256均为 `14C3ABD5A3B28BE275276E4920A8A25E13CBB3FB4249A59C418076ABE42DB4A6`；核对身份、事件、订单、成交、清算、转账、快照及入金表，没有重新配资。
- 再执行 `start.ps1 -NoBrowser`：七个Java PID不变、跳过Maven、RAG源码/健康缓存命中；网关session和新版页面200。`git diff --check`无补丁空白错误；私密配置、运行状态及Python缓存被忽略。未提交、推送或切换分支。

当前可自行登录提问“如何充值”“限价单规则是什么”“部分成交后撤单会怎样”，展开引用核对原文。真实供应商的回答质量未包含在上述通过结果内；依赖故障、未启用生成和证据不足会显示各自状态，不承诺所有自然问法命中。
