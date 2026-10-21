# 智采云 SmartProcure

当前交付：**M3 付款核心 Mock 实现，Chrome 验收待完成**。在已验收 M2 映射、报价和异步采购之上，增加 MANUAL/AUTO/RULE_BASED、Mock签约、独立付款尝试、并发预算与未知结果只读对账。自动化和验收进展见 `docs/30_M3实施计划与验收.md`；M2 已验收记录保留在 `docs/29_M2实施计划与验收.md`。所有付款结果均为合成 Mock；真实平台通道关闭，物流尚未实现。

## 快速启动（Docker）

需要 Docker Engine/Desktop 与 Compose **2.24+**；仅用于本地演示，不要部署公网。基础设施凭据由工具随机生成到 Git 忽略的 `.env`，不填写生产信息。

```bash
python3 scripts/init-demo-env.py
docker compose config --quiet
docker compose up --build --wait --wait-timeout 300
```

打开 [本地 Web](http://localhost:4173/login)。选择“商家 A · 店主”进入工作台；打开店铺中心查看两个合成店铺，再进入订单工作台生成 Mock 事件。“客服”仅可查看 A 的一个店铺，“商家 B”用于隔离验收。均为合成身份选择，无账号密码。API [健康检查](http://localhost:8080/actuator/health/liveness)、[本地 Swagger](http://localhost:8080/swagger-ui/index.html)。已有 `.env` 时初始化工具会拒绝覆盖，请直接复用。

停止：`docker compose down`；PostgreSQL 数据卷会保留。删除数据卷会丢失本地演示数据，不作为日常重启步骤。重启 API 会撤销全部内存演示会话，重新登录即可。

如果本机已有 PostgreSQL、Redis 或其他服务占用默认端口，可在 `.env` 中设置 `.env.example` 列出的 `*_HOST_PORT`（例如 `POSTGRES_HOST_PORT=55433`、`REDIS_HOST_PORT=56380`）。容器间连接仍使用固定内部端口，宿主端口仍只绑定127.0.0.1；不会停止其他项目的服务。修改 `API_HOST_PORT` / `WEB_HOST_PORT` 后，相应访问地址也需使用该端口。

## 本地开发（Java 21 / Node 24）

根 `pom.xml` 聚合 `apps/api`；Maven Wrapper 固定 3.9.16。数据库需 PostgreSQL17，Redis8，RabbitMQ4.1；也可只用 Compose 启动这三个服务。数据库使用专用演示库，不使用已有生产库。

```bash
docker compose up -d postgres redis rabbitmq
set -a
. ./.env
set +a
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/smartprocure
export SPRING_DATASOURCE_USERNAME=smartprocure
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export SPRING_DATA_REDIS_PASSWORD="$REDIS_PASSWORD"
export SPRING_RABBITMQ_USERNAME=smartprocure
export SPRING_RABBITMQ_PASSWORD="$RABBITMQ_PASSWORD"
export APP_MOCK_WEBHOOK_SECRET="$M1_MOCK_WEBHOOK_SECRET"
./mvnw -pl apps/api spring-boot:run -Dspring-boot.run.profiles=demo
```

另一个终端：

```bash
cd apps/web
npm ci
npm run dev
```

打开 [Vite 本地 Web](http://127.0.0.1:5173/login)，Vite 将 `/api` 代理至8080。原生服务可用 `SPRING_DATA_REDIS_HOST/PORT`、`SPRING_RABBITMQ_HOST/PORT` 调整连接；依赖故障会显示 `DOWN`，不会冒充正常服务。

## 演示数据与安全边界

- Flyway V1/V2 保持不变，V3 增量管理 M2 映射、报价、策略和采购约束，禁止 `ddl-auto=update`。支付及履约草案表存在不代表业务开放。
- 仅 `demo` / `test` 配置加载独立种子：2租户、3合成身份、每租户2店铺和3个合成货源；客服按 member_store_access 只读 A 的第一家店及其采购。重复初始化不重置店铺/货源配置，也不预建已确认映射、采购或付款；销售单由 Mock 场景生成，采购单由后续已确认映射与规则决定，不含消费者隐私。
- `SPSESSION` 为 HttpOnly/SameSite cookie，30分钟失效；每请求查验 ACTIVE 用户、成员、角色与租户。普通请求不能通过 Header/query/body 切换租户。会话仅本地单实例内存，生产认证、共享会话和正式安全准入尚未完成。
- 演示写请求必须先 `GET /api/v1/auth/csrf`，携带返回的 `X-CSRF-TOKEN`；登录后重新获取。退出立即撤销；前端不保存认证 token 到 localStorage。
- `APP_PROVIDER_MODE=MOCK`，`APP_REAL_PLATFORM_ENABLED`、`APP_REAL_PROCUREMENT_ENABLED`、`APP_REAL_PAYMENT_ENABLED` 默认 false；启用真实模式会失败关闭。`prod` 与演示配置组合被拒绝，不存在真实 Adapter、支付脚本或第三方密码收集。
- M0/M1 与 M2 映射、Mock报价、策略、采购意图/采购单以 `contracts/openapi.yaml` 为源；支付/物流等其余路径为未来设计，未实现。

## M1 合成事件演示

店铺中心支持注册、改名、禁用/启用及授权有效/过期/撤销模拟。授权到期和禁用均停止新同步，保留历史订单只读；撤销/禁用需确认。

订单工作台选择店铺、外部订单号和场景：PAID、DUPLICATE（同事件10次）、OUT_OF_ORDER、DELAYED、REFUNDED。事件经签名校验、Inbox/Outbox 持久化后由数据库 Worker 重查独立 Mock 权威快照；页面稍后刷新可见。乱序事件不能把最新源版本回退。M1 使用数据库持久任务轮询，不声称 RabbitMQ exactly-once；RabbitMQ 当前仍为基础设施依赖。

Mock Webhook 只接受安全引用字段，HMAC 覆盖 `timestamp.nonce.integrationId.rawBody`，不接受客户端租户或付款状态。初始化工具在 `.env` 生成随机 `M1_MOCK_WEBHOOK_SECRET`；原生服务用 `APP_MOCK_WEBHOOK_SECRET` 注入。已有 M0 `.env` 未包含该键时可自行添加随机演示值；未提供时 demo/test 使用明确公开的伪默认 `MOCK_ONLY_LOCAL_WEBHOOK_KEY_NOT_FOR_PRODUCTION`。该机制只用于本地模拟，不可用作真实平台或公网认证。

HTTP验收脚本依次从环境变量、项目 `.env`、公开伪默认读取该特定键，绝不打印签名密钥。M1 HTTP 可指定 `M1_BASE_URL=http://localhost:4173/api/v1`；使用原生公开伪默认时请显式提供相同值，避免被已有容器 `.env` 覆盖。

M1 原验收记录保留在 `docs/28_M1实施计划与验收.md`；M2 增量覆盖采购前退款复核和已存在采购对象的隔离。T-014 的支付策略权限流程已在 M3 增量覆盖；同账号多租户切换尚未实现，T-021 验证独立会话并发和重新登录身份轮换。

## M2 可重复合成演示

1. 店主在订单工作台选一个有效店铺生成 PAID 或 DUPLICATE 事件，等待销售单落库；没有已确认映射时不会采购。
2. 从销售订单子项打开“商品与货源”，用 `MOCK-SKU-BLUE` 或 `MOCK-SKU-WHITE` 对照合成货源规格，创建 REVIEW 映射并明确核对后确认。蓝色 SKU 有两个独立备选供应商，可分别确认；标题相似不会自动确认。
3. 获取报价/规则预览；检查整数分的商品总价、运费、预计利润和结构化阻塞原因。只在报价新鲜、授权有效、库存/密文/地区/金额规则通过时自动产生唯一采购意图；也可用预览报价显式申请，同一子项不会重复采购。
4. “采购管理”查看异步结果。成功只到 **Mock 待付款**，没有支付动作。将货源故障设置为“创单后超时”再生成一个新销售单，可看到结果未知冻结和随后查单恢复；“创单前超时”查不到后转人工，绝不自动重下。演示结束可把故障恢复“正常”。
5. 缺货、报价超时、仅支持另一来源平台密文、超限或利润不足均应阻断。单价/运费或配置在意图生成后变化，会在创单前复核时暂停；不会沿用旧报价静默加价。

每个子项本轮仅支持一个履约切片。REVIEW_REQUIRED 或查单仍不明的 CREATE_UNKNOWN 会保留并冻结，未提供人工强制重下或付款入口；后续人工处置/售后不属于 M2。日额度按 UTC 采购意图创建日统计，包含在途及未知项，与支付预算分离；默认值仅用于 Mock（租户单笔100000分/日1000000分，店铺单笔50000分/日200000分）。

`scripts/smoke-m2.py` 只用于专用本地演示环境：每次创建独立合成店铺/销售单，临时提高 Mock 租户采购限额并恢复；暂停本次映射、恢复货源配置，保留合成历史便于复核。可指定 `M2_BASE_URL=http://localhost:4173/api/v1`。Worker 可用 `APP_MOCK_WORKER_ENABLED=false` 总体关闭；测试通过专用 schema 和关闭调度控制时序，不拿异步等待替代断言。

## M3 可重复付款模拟（Chrome 待验收，记录见 docs/30）

M3仅运行本地Mock：PaymentPolicy的MANUAL/AUTO/RULE_BASED、签约状态、付款尝试/预算、异步Worker和只读对账。初始策略为MANUAL且暂停，签约PENDING；真实通道仍关闭。租户停付作用于所有店铺，独立平台总停付由`APP_PAYMENT_EMERGENCY_STOP=true`或全局feature flag `payment_emergency_stop`控制；均不会阻止在途只读查询。本轮不扩充平台管理员控制台。

三个脚本通过HTTP调用合成适配器，无浏览器收银台操作、密码、验证码或真实付款。请仅在专用本地演示运行；默认API8080、Web4173，可按本机端口调整：

```bash
M3_BASE_URL=http://localhost:8080/api/v1 .local/venv/bin/python scripts/demo-m3-auto.py
M3_BASE_URL=http://localhost:8080/api/v1 .local/venv/bin/python scripts/demo-m3-manual.py
M3_BASE_URL=http://localhost:8080/api/v1 .local/venv/bin/python scripts/demo-m3-unknown.py
```

自动脚本启用有效Mock签约并等待权威已付；人工脚本先证明UNPAID回执保持待付款，再模拟PAID权威回执并等待Worker复核；未知脚本配置持续查单超时，验证人工/自动入口均拒绝，再查询同一attempt。输出`runtimeMode=MOCK`和合成单据ID，人工脚本不是对真实付款页的自动点击。可设置`M3_WEB_URL`调整输出的本地详情链接。

脚本新建独立店铺/销售单和已审核映射，保留历史供复核；收尾暂停本次映射、恢复采购策略，并将付款策略设回MANUAL+暂停，避免历史待付数据被后台自动处理。原PENDING签约会保守恢复为REVOKED（Mock更新接口仅允许ACTIVE/EXPIRED/REVOKED）；其他被修改签约恢复原状态/上限，ACTIVE会重新获得合成有效期。不要把它们用于生产或用户正在操作的共享演示环境。

UNKNOWN或MANUAL_RECONCILIATION只能核实原attempt，禁止再次付款或返回人工入口；明确FAILED在M3也保留为终态，不提供新attempt重付。自动查单至多5次，后转人工冻结；显式只读对账仍可核实同一attempt。所有已付金额均指Mock权威流水，未接入真实平台资金。

## 构建与测试

单元测试与 API jar：

```bash
./mvnw --batch-mode verify
```

真实 PostgreSQL 集成测试必须使用**专用测试库**；测试会迁移、初始化并修改其中的合成数据，且会创建独立的非演示测试 schema。默认命令不执行 IT，不能将单元测试成功表述为集成验证。

```bash
export M0_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/smartprocure_test
export M0_TEST_DB_USER=smartprocure
export M0_TEST_DB_PASSWORD="$POSTGRES_PASSWORD"
./mvnw --batch-mode -Pintegration verify
```

```bash
cd apps/web
npm ci
npm run lint
npm run typecheck
npm test
npm run build
npm audit --audit-level=high
```

合同校验、基础安全扫描与运行中 API 的 HTTP 验收（在项目根执行）：

```bash
python3 -m venv .local/venv
.local/venv/bin/pip install -r scripts/requirements.txt
.local/venv/bin/openapi-spec-validator contracts/openapi.yaml
.local/venv/bin/python apps/web/scripts/generate-m2-contract.py --check
.local/venv/bin/python apps/web/scripts/generate-m3-contract.py --check
python3 scripts/check-safety.py
.local/venv/bin/python scripts/smoke-m0.py
.local/venv/bin/python scripts/smoke-m1.py
.local/venv/bin/python scripts/smoke-m2.py
.local/venv/bin/python scripts/smoke-m3.py
M0_REQUIRE_HEALTHY=1 M0_BASE_URL=http://localhost:4173/api/v1 .local/venv/bin/python scripts/smoke-m0.py
```

最后一条要求完整 Compose 的所有依赖为 UP，并验证 Nginx 代理、会话及CSRF。基础扫描只检查常见密钥模式和意外真实电商 URL，配合 npm audit；不代表生产安全审计或完整 Java 漏洞扫描。

CI 位于 `.github/workflows/ci.yml`，含后端单元/PG集成、前端 lint/typecheck/test/build/audit、合同及生成DTO一致性、基础安全，以及 Compose 两次启动后的 M0/M1/M2 HTTP 验收。M2 结果以 [docs/29](docs/29_M2实施计划与验收.md) 的真实执行记录为准。M1历史验收后端62/62、前端49/49，当前M1交付提交4e5e863的[真实GitHub Actions](https://github.com/zhangguoli2008/TB21688/actions/runs/39540075859)四job通过。M0/M1历史证据仍保存在docs/27和docs/28，不能代替M2验收。

Spring Boot **3.5.16** 遵循既有3.x基线；该分支开源支持已结束，生产前需要明确升级或商业支持决策，见 [Spring 官方支持记录](https://api.spring.io/projects/spring-boot/generations/3.5.x)。本项目目前不满足正式上线准入。

---

以下保留原需求基线与后续阅读顺序。

**版本**：需求基线 v1.0.0（2026-10-20）  
**状态**：可启动 Mock-first 开发；**不代表各电商平台接口或支付能力已获批**  
**产品**：面向淘宝/天猫、抖音商家的 B/S 多租户自动采购 SaaS；优先对接 1688，拼多多仅预留受控适配器。

## 交付物清单与阅读顺序

1. `AGENTS.md`：交给 Codex 的全局工程约束；开始前必读。
2. `docs/01_项目章程与范围.md`、`02_产品需求PRD.md`、`03_竞品功能矩阵.md`：明确做什么、不做什么。
3. `docs/04_业务规则与用户故事.md`、`05_业务流程与状态机.md`：业务细则。
4. `docs/06_系统架构与技术选型.md`、`07_数据库设计.md`、`database/schema.sql`：工程设计。
5. `docs/08_API规范.md`、`contracts/openapi.yaml`、`docs/09_平台接入与授权.md`：接口合同与渠道边界。
6. `docs/10_支付方案与风控.md`、`11_隐私与安全合规.md`：高风险环节。
7. `docs/12_前端页面与交互.md`、`13_货源与AI匹配.md`、`14_物流与售后.md`：各子系统设计。
8. `docs/15_SaaS计费与权限.md`、`16_部署运维.md`、`17_测试与验收.md`：商用能力。
9. `docs/18_开发里程碑与任务池.md`、`19_风险清单与待确认问题.md`、`20_供应商官方咨询清单.md`：落地计划。
10. `docs/22_接口任务可追踪矩阵.md`、`23_核心页面线框图.md`、`24_非功能需求与SLO.md`、`25_项目验收清单.md`：接口追踪、页面线框、性能指标及签收标准。
11. `prompts/00_第一次发给Codex.md` 至 `08_验收与问题修复.md`：逐阶段复制给 Codex 的实施指令。
12. `adr/ADR-001_关键架构决策.md`、`diagrams/*.mmd`：架构决策和图。

## 确定的基线

- Web：Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router。
- API：Java 21 + Spring Boot 3.x（实际最新维护小版本在实施时锁定）、Spring Security、Flyway、JPA/JDBC（关键查询明确 SQL）。
- 数据：PostgreSQL；Redis；RabbitMQ；对象存储抽象；OpenTelemetry、Prometheus。
- 租户：**共享服务 + 行级 tenant_id 隔离 + 关键查询二次鉴权**；运营后台与商家后台分离权限域。
- 对接：平台 `Adapter`/防腐层；`MOCK` 为默认；真实服务必须由对应权限审批与开关显式启用。
- 支付：`MANUAL` / `AUTO` / `RULE_BASED`；**不托管采购资金、不保存第三方账户密码或支付验证码**。
- 部署：开发环境 Docker Compose；一期模块化单体 + 多 Worker；后期按瓶颈拆分。

## MVP 的完成定义

**用 Mock 实现并跑通**：租户注册 → 创建店铺并模拟授权 → 模拟订单进入 → 匹配确认的供应商 SKU → 报价/运费/利润校验 → 创建上游采购单 → 人工支付模拟 / 经有效 Mock 授权自动支付模拟 → 对账 → 供应商模拟发货 → 销售平台模拟发货回填 → 售后取消/退款流程 → 审计与报表。

> Mock 成功不代表平台生产可用。生产集成需要独立的审批材料、签约协议、真实沙箱测试以及灰度验收。

## 首次使用 Codex

1. 文档已导入仓库根目录；原始解压包与压缩包保留在本地并由 Git 忽略。
2. 先读 `AGENTS.md` 和 `prompts/01_初始化项目.md`。
3. 一次只实施一个里程碑，Codex 完成代码、测试、接口样例和更新文档后再验收。
4. 不要把任何生产 token、商家隐私数据、免密支付密钥填入提示词或示例配置。
5. 关注 `docs/19_风险清单与待确认问题.md` 的 **GATE-** 条目，未通过不得开启对应生产通道。

## 商业/技术免责声明

- 此为**独立产品需求与架构设计**，参考公开可见的行业功能分类；不包含乐客源码、受版权保护的截图、私人接口及商标资产。
- `contracts/openapi.yaml` 是**我们自有 SaaS API 合同**，不是淘宝、抖店、1688、拼多多官方接口说明。
- 2026-10-20 核查起点见 `docs/21_来源与核查记录.md`。各平台政策、API 权限、收费及准入资格将变化，生产实施前必须复核。
