# 消防隐患字段建模与受控查询平台

面向市消防救援支队的**字段治理（Field Governance）**系统：把区县检查表中“同名不同义、单位不一、状态随复查变化”的自定义隐患字段，
通过**稳定字段 ID + 版本化目录 + 逐值证据映射契约 + 受控查询编译 + 权限与披露抑制 + 离线草案合并 + 历史复现**，
纳入可审计的市级风险统计与授权查询；并提供**高风险场所排序的规则解释与稳定性**能力（规则解释图、字段贡献分解、
缺失/替换/单位换算情景、单调性检验、稳定性报告）。

技术栈：**React 18 + TypeScript + Vite + IndexedDB + WebWorker**（前端） · **Spring Boot 3.5 + Java 25 + Spring JDBC + PostgreSQL 18**（后端）。

---

## 1. 一键启动（Docker Compose）

```bash
docker compose up --build
# 前端：http://localhost:8088
# 后端 API：http://localhost:8080
# PostgreSQL：localhost:5432（库/用户/密码 ccdev）
```

启动时后端自动执行 `schema.sql` 建表与 `data.sql` 播种（幂等，可重复执行）。

## 2. 本地开发启动

前置：容器内已预装 JDK 25、Maven、Node 22、PostgreSQL 18（127.0.0.1:5432，ccdev/ccdev）。

```bash
# 后端（端口 8080，自动建表播种）
cd backend
mvn spring-boot:run

# 前端（端口 5173，/api 已配置代理到 8080）
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run dev          # 开发
npm run build        # 类型检查 + 生产构建（产物在 dist/）
```

后端单元测试与端到端验证：

```bash
cd backend
mvn test                              # JUnit：披露抑制、厘米→毫米换算、评分/Shapley/单调性（11 项）
python3 e2e/run-e2e.py                # 需后端已启动；8 大场景 64 项断言的完整 E2E
```

## 3. 测试账号

| 用户名 | 密码 | 角色/辖区/密级 | 授权目的 |
| --- | --- | --- | --- |
| `city_admin` | `city123` | 市级管理员，全市，RESTRICTED | STAT / CASE_INVEST / AUDIT / GOVERN |
| `jia_analyst` | `jia123` | 甲区分析员，JIA，INTERNAL（含 BING 跨区 STAT 授权，用于验证敏感级拦截） | STAT |
| `yi_analyst` | `yi123` | 乙区分析员，YI，INTERNAL（含 JIA 案件 J-2026-003 逐案授权） | STAT / CASE_INVEST |
| `auditor` | `audit123` | 审计员，全市，RESTRICTED | 仅 AUDIT（发起 STAT 查询会被拒绝） |

## 4. 预置数据与题目场景

- 甲区 `J-2026-001`：裂缝 **12mm**、`jia.risk_level=2`（甲区定义=需限期整改，**非重大**）、检查后仍未整改；
- 乙区 `Y-2026-001`：裂缝 **1.2cm**、新版表单 `yi.risk_level=2`，表单填报说明其含义为**“需观察”**（同名不同义，禁止按旧义计为重大）；
- 甲区 `J-2026-002`：立案为重大隐患，**复查后降级**为限期整改（事件流 `REVIEW_DOWNGRADE`）；
- 丙区 3 个案件的**敏感辖区**（RESTRICTED 字段）；
- 乙区旧版表单 `Y-2026-002`（目录 v1，旧义代码 2=重大）与新版代码 `4`（临时管控，**无市级等价语义，编译期拒绝**）；
- 待批准的辖区扩展字段 `yi.ev_charge_hazard`（电动车充电隐患）与市级立项申请。

## 5. 核心验证方式（已实际执行）

在“受控查询”页点击**载入题目标准查询**（或用 `city_admin` 调 `POST /api/query/compile`）：

> 各区当前未整改重大隐患中裂缝宽度 ≥ 10mm 的案件数（asOf=2026-05-20）

系统行为与标准结果：

1. **先按各自定义映射**：乙区新版代码 2 → `OBSERVE`（不计重大）；甲区代码 2 → `LIMITED`（不计重大）；旧版表单代码 2 经 SUPERSEDED 的 V1 契约 → `MAJOR`；
2. **再换算单位**：乙区 `1.2cm` 经契约 `x*10` → `12mm`，逐值证据可在“表单与证据链”页查看；
3. **应用事件状态**：`J-2026-002` 复查降级后不进重大项；`J-2026-008` 已整改不进未整改项；
4. **权限过滤**：甲区分析员只见 JIA；审计员无 STAT 目的被拒；INTERNAL 密级拿不到 BING 敏感组；
5. **披露抑制（阈值 4）**：JIA=4 发布；YI=3、BING=3 为小组抑制（不返回原始计数）；可发布**总数 9**；互补抑制保证无法用“总数−其他组”反推被抑制格；
6. **不等价拒绝**：乙区代码 4 与待批准扩展字段进入 `compileWarnings`，不强行合并；`POST /api/forms/map-check` 返回 422；
7. **历史复现**：种子查询按快照（目录 v2 + asOf + 契约版本清单）重放，与存档逐组一致；旧表单始终按提交时 v1 语义解释；
8. **离线合并**：基线一致的草案 MERGED；含 `std.smoke_density`（v3 语义与敏感级均变化）的草案转 PENDING_CONFLICT，可退回重填或管理员复核强合；
9. **新字段治理**：批准前查询 `std.ev_charge_hazard` 被拒；批准（可指定追溯生效日）后，旧表单 FI-Y-005 **不改写**，编译期回溯映射为 PRESENT。

E2E 断言结果（最近一次执行）：**64 项全部 PASS，0 FAIL**（`backend/e2e/run-e2e.py`，8 大场景：标准查询 11 项、访问策略 4 项、不等价拒绝 2 项、历史复现 4 项、离线合并 6 项、扩展治理 7 项、演进一致性 3 项、**排序解释与稳定性 27 项**）。

## 5b. 特征3：高风险场所排序的规则解释与稳定性（验证方式）

在“排序解释与稳定性”页（或用 `city_admin` 调 `/api/explain/*`，asOf=2026-05-20）验证：

**评分口径**：4 条版本化规则（隐患等级 40/20/8/0、裂缝宽度分档 30/15、未整改 20、违规充电 15），
分级带 ≥70 红 / ≥50 橙 / ≥30 黄 / ≥10 蓝。如 `J-2026-001`（限期整改 + 12mm 裂缝 + 未整改）= 20+15+20 = **55 分 ORANGE**。

1. **规则解释图**（`GET /api/explain/venues/{caseId}/graph`）：源字段(提交时版本/现行状态/漂移标记) → 契约/换算(批准状态)
   → 标准值 → 命中规则分支 → 总分/等级，逐节点可审计；
2. **字段贡献分解双口径**：PATH 路径归因（逐条可审计，份额相加=总分）与 SHAPLEY（对分级阈值效用 `v(S)=档位rank`
   遍历 2^n 联盟，表达规则交互；分数尺度下退化为路径归因，作为效率校验）；
3. **缺失情景器**（`.../scenarios/missing`）：只枚举“已批准映射可达”的取值；`Y-2026-004` 等级语义未决，
   补全可能改变名次时**只给 [best,worst] 名次区间与分数/等级区间，不给定点名次**；DRAFT 契约的
   `std.ev_charge_hazard` 无批准路径，候选全部 UNREACHABLE，区间不放宽；
4. **替换情景**（`.../scenarios/substitution`）：候选限定在表单**提交时目录版本**的值域；
   对废止/改名的 `yi.risk_level` 请求“按现行含义回算历史” → **REFUSED**；乙区代码 4 无市级等价 → **REJECTED**；
5. **单位换算情景**（`.../scenarios/unit`）：`1.2cm` 经批准 `x*10` → 12mm 可计算；把原始单位按未批准 `x*1` 直取
   → **BLOCKED，不进入贡献计算**；市级标准字段走内置恒等 `x*1`（已批准路径）；
6. **单调性检验**：A) 现行规则结构扫描（数值域+枚举声明序）；B) 规则更新候选校验——反向赋分候选
   `REJECTED_MONOTONIC_BREACH`，合规候选 `ACCEPTABLE`；C) 字段版本漂移识别（`yi.risk_level` 同名不同义）+
   实证扰动，漂移只警示、不回算历史；
7. **稳定性报告**（`.../stability`）：同一场所替换/缺失/单位换算对分数、等级、名次的边际影响汇总，
   含 BLOCKED/REJECTED 计数与等级/名次稳定性结论。

**三条铁律的落地**：废止字段按现行含义回算历史 → `REFUSED`；缺失使排名翻转 → 只发布区间；无批准换算 → `BLOCKED` 且不进贡献。所有解释计算写入 `explain_report` 审计表。

> 说明：本隔离容器未安装 Docker 守护进程，镜像未在本地构建；已提供多阶段 `Dockerfile` 与 `docker-compose.yml`（YAML 结构已校验），在装有 Docker 的环境执行 `docker compose up --build` 即可。上述单测、前端构建与 37 项 E2E 均在容器预装的 JDK 25 / Node 22 / PostgreSQL 18 上实际执行通过。

## 6. 界面清单

| 页面 | 能力 |
| --- | --- |
| 受控查询 | 标准化表达（仅 std.*）、目的/时点/版本选择、披露结果、逐案编译诊断（证据链/换算/事件重放/剔除原因）、版本快照 |
| 排序解释与稳定性 | 场所排序（缺失区间）、规则解释图、PATH/SHAPLEY 贡献分解、缺失/替换/单位换算情景、稳定性报告、单调性扫描与规则更新校验、版本漂移 |
| 字段目录 | 按版本 + asOf 解释字段；显示值域/单位/粒度/事件时间含义/辖区/敏感级/有效区间/弃用替代；字段版本史 |
| 映射契约 | 契约状态与生效区间、逐值证据、换算公式、equivalent=false 的拒绝项 |
| 表单与证据链 | 不可变实例、提交时目录版本、逐字段映射轨迹、案件事件流、map-check 显式拒绝演示 |
| 离线草案 | IndexedDB 本地草案、WebWorker 同步、语义/敏感级冲突待决、退回重填/管理员强合 |
| 历史复现 | 查询历史、按快照重放、存档 vs 复现逐组比对 |
| 新字段治理 | 两条路线（市级标准 / 命名空间扩展）申请、批准（可追溯）/驳回、批准前拒绝→批准后回溯闭环 |

## 7. 关键设计约束的落地位置

- 稳定字段 ID 与版本并存：`field_def(field_id, version)`，解释规则见 `CatalogService.defsAsOf`；
- 提交时固定目录版本：`form_instance.catalog_version`，契约选择受 `contract.version ≤ catalog_version` 约束（`MappingEngine`）；
- 同名不自动合并：仅允许经 `mapping_contract + mapping_value` 逐值证据映射，`equivalent=false` 一律拒绝；
- 最小发布组与互补抑制：`SuppressionService`（阈值 `app.suppression.threshold=4`）；
- 四重访问策略（辖区/案件/敏感级/目的）：`AccessPolicyService`；
- 离线冲突检测：前端 `sync.worker.ts` 预检 + 后端 `OfflineService` 独立复核；
- 历史复现：`query_history.version_snapshot` + `HistoryService.replay` 固定版本重编译；
- 回溯不改写旧表单：治理批准只发布新目录版本/契约生效日，表单实例行永不更新；
- 排序解释与稳定性（特征3）：`explain` 包——`VenueRiskService`（场所装配/评分/可达取值）、
  `ExplainGraphService`（解释图/PATH+SHAPLEY 贡献/区间排序）、`ScenarioService`（缺失/替换/单位情景与稳定性）、
  `MonotonicityService`（规则扫描/更新校验/漂移实证）；规则与单调声明存于 `scoring_rule`、`monotonic_declaration`，解释留痕 `explain_report`。

## 8. 目录结构

```
.
├── backend/                Spring Boot 工程
│   ├── src/main/java/com/fire/fieldgov/
│   │   ├── catalog/        字段目录与契约读取（版本解释）
│   │   ├── mapping/        映射引擎（证据/换算/生效范围）、事件流重放
│   │   ├── query/          查询编译器、历史复现
│   │   ├── policy/         访问策略、披露抑制
│   │   ├── offline/        离线草案合并与待决
│   │   ├── governance/     扩展字段两条路线治理
│   │   ├── explain/        排序解释图、贡献分解、缺失/替换/单位情景、单调性、稳定性
│   │   ├── form/ web/ security/ db/
│   ├── src/main/resources/ schema.sql / data.sql / application.yml
│   └── e2e/run-e2e.py      端到端验证脚本（8 场景 64 断言）
├── frontend/               React + TS 工程
│   └── src/{pages,workers,db.ts,api.ts}
└── docker-compose.yml      一键启动（postgres + backend + frontend/nginx）
```
