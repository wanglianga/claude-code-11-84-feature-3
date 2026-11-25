-- 消防隐患字段治理：PostgreSQL schema（幂等，可重复执行）
-- 命名约定：std.* 市级标准字段；<辖区>.* 辖区命名空间扩展字段

CREATE TABLE IF NOT EXISTS app_user (
    username        VARCHAR(64) PRIMARY KEY,
    display_name    VARCHAR(128) NOT NULL,
    password        VARCHAR(128) NOT NULL,
    role            VARCHAR(32)  NOT NULL,            -- CITY_ADMIN / DISTRICT_ANALYST / AUDITOR
    home_district   VARCHAR(16),                      -- NULL 表示全市
    clearance       VARCHAR(16)  NOT NULL DEFAULT 'INTERNAL', -- INTERNAL / RESTRICTED
    allowed_purposes TEXT[]      NOT NULL DEFAULT '{}',       -- STAT / CASE_INVEST / AUDIT / GOVERN
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_case_grant (
    username   VARCHAR(64) NOT NULL REFERENCES app_user(username),
    district   VARCHAR(16) NOT NULL,
    case_id    VARCHAR(64) NOT NULL DEFAULT '*',    -- '*' 表示辖区内全部案件
    purpose    VARCHAR(32) NOT NULL,
    PRIMARY KEY (username, district, case_id, purpose)
);

-- 字段目录版本（每次发布一行；当前版本取最大 version）
CREATE TABLE IF NOT EXISTS field_catalog_version (
    version       INT PRIMARY KEY,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    note          TEXT
);

-- 字段定义：同一 field_id 随目录版本多行；查询按“≤ 请求版本的最新一行”解释
CREATE TABLE IF NOT EXISTS field_def (
    field_id          VARCHAR(64) NOT NULL,
    version           INT NOT NULL,
    display_name      VARCHAR(128) NOT NULL,
    definition        TEXT NOT NULL,
    value_domain      JSONB NOT NULL,               -- {"type":"enum|numeric","values":[]} / {"min":,"max":}
    unit              VARCHAR(32),
    granularity       VARCHAR(64),                  -- 案件级 / 单次测量 / 案件-复查级
    event_time_meaning VARCHAR(128),                -- 该字段取值对应的业务事件时间含义
    applicable_districts TEXT[] NOT NULL DEFAULT '{}', -- 空数组表示全市适用
    sensitivity       VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
    valid_from        DATE NOT NULL,
    valid_to          DATE,
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / DEPRECATED / DRAFT
    namespace         VARCHAR(16) NOT NULL DEFAULT 'CITY',   -- CITY / JIA / YI / BING
    superseded_by     VARCHAR(64),                  -- 弃用替代字段
    PRIMARY KEY (field_id, version)
);

-- 映射契约：源代码字段 -> 标准字段，带生效区间与审查状态
CREATE TABLE IF NOT EXISTS mapping_contract (
    contract_id      VARCHAR(64) PRIMARY KEY,
    source_field_id  VARCHAR(64) NOT NULL,
    target_field_id  VARCHAR(64) NOT NULL,
    source_district  VARCHAR(16) NOT NULL,
    version          INT NOT NULL,
    status           VARCHAR(16) NOT NULL,          -- APPROVED / DRAFT / SUPERSEDED / REJECTED
    effective_from   DATE NOT NULL,
    effective_to     DATE,
    evidence_note    TEXT NOT NULL,
    reviewed_by      VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 逐值映射：每个源代码取值一行，禁止同名自动合并
CREATE TABLE IF NOT EXISTS mapping_value (
    contract_id   VARCHAR(64) NOT NULL REFERENCES mapping_contract(contract_id),
    source_value  VARCHAR(64) NOT NULL,
    target_value  VARCHAR(64),
    transform     VARCHAR(128),                     -- 数值换算公式，如 x*10、x*1
    evidence      TEXT NOT NULL,                    -- 该取值映射的证据出处
    equivalent    BOOLEAN NOT NULL DEFAULT TRUE,    -- FALSE = 无法证明语义等价，编译时拒绝
    PRIMARY KEY (contract_id, source_value)
);

-- 表单模板：提交时固定目录版本
CREATE TABLE IF NOT EXISTS form_template (
    template_id     VARCHAR(64) PRIMARY KEY,
    district        VARCHAR(16) NOT NULL,
    catalog_version INT NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS form_template_field (
    template_id VARCHAR(64) NOT NULL REFERENCES form_template(template_id),
    field_id    VARCHAR(64) NOT NULL,
    ordinal     INT NOT NULL DEFAULT 0,
    PRIMARY KEY (template_id, field_id)
);

-- 表单实例（不可变；提交后内容哈希固定，回溯映射不改写本行）
CREATE TABLE IF NOT EXISTS form_instance (
    instance_id     VARCHAR(64) PRIMARY KEY,
    case_id         VARCHAR(64) NOT NULL,
    template_id     VARCHAR(64) NOT NULL,
    district        VARCHAR(16) NOT NULL,
    catalog_version INT NOT NULL,                   -- 提交时固定的目录版本
    inspect_date    DATE NOT NULL,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitter       VARCHAR(64) NOT NULL,
    content_hash    VARCHAR(128) NOT NULL,
    origin          VARCHAR(16) NOT NULL DEFAULT 'ONLINE' -- ONLINE / OFFLINE_MERGE
);
CREATE INDEX IF NOT EXISTS idx_form_case ON form_instance(case_id);

CREATE TABLE IF NOT EXISTS form_value (
    instance_id VARCHAR(64) NOT NULL REFERENCES form_instance(instance_id),
    field_id    VARCHAR(64) NOT NULL,
    value_text  VARCHAR(256) NOT NULL,
    PRIMARY KEY (instance_id, field_id)
);

-- 案件事件流：隐患等级/整改状态按事件时间重放
CREATE TABLE IF NOT EXISTS case_event (
    event_id    BIGSERIAL PRIMARY KEY,
    case_id     VARCHAR(64) NOT NULL,
    district    VARCHAR(16) NOT NULL,
    event_type  VARCHAR(32) NOT NULL,               -- INSPECTION / REVIEW_DOWNGRADE / REVIEW_RESCIND / RECTIFY
    event_time  TIMESTAMPTZ NOT NULL,
    payload     JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_event_case_time ON case_event(case_id, event_time);

-- 离线草案合并结果
CREATE TABLE IF NOT EXISTS offline_draft (
    draft_id              VARCHAR(64) PRIMARY KEY,
    username              VARCHAR(64) NOT NULL,
    client_id             VARCHAR(128) NOT NULL,
    base_catalog_version  INT NOT NULL,
    payload               JSONB NOT NULL,
    status                VARCHAR(24) NOT NULL,     -- MERGED / PENDING_CONFLICT / REBASED
    conflicts             JSONB NOT NULL DEFAULT '[]',
    merged_instance_id    VARCHAR(64),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    synced_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (username, client_id)
);

-- 受控查询历史（含版本快照，供历史复现）
CREATE TABLE IF NOT EXISTS query_history (
    query_id         BIGSERIAL PRIMARY KEY,
    username         VARCHAR(64) NOT NULL,
    purpose          VARCHAR(32) NOT NULL,
    query_json       JSONB NOT NULL,
    result_json      JSONB NOT NULL,
    version_snapshot JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(64) NOT NULL,
    action     VARCHAR(64) NOT NULL,
    detail     JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 辖区扩展字段治理请求（两条路线：市级标准 / 命名空间扩展）
CREATE TABLE IF NOT EXISTS extension_request (
    request_id        VARCHAR(64) PRIMARY KEY,
    route             VARCHAR(24) NOT NULL,         -- CITY_STANDARD / NAMESPACE_EXT
    district          VARCHAR(16),
    proposed_field_id VARCHAR(64) NOT NULL,
    field_def_json    JSONB NOT NULL,
    contract_json     JSONB,
    status            VARCHAR(16) NOT NULL DEFAULT 'DRAFT', -- DRAFT / APPROVED / REJECTED
    evidence          TEXT,
    catalog_version_introduced INT,
    reviewed_by       VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at       TIMESTAMPTZ
);

-- ============ 特征3：高风险场所排序的规则解释与稳定性 ============

-- 排序评分规则（可版本化；规则更新与已声明单调关系的冲突由解释服务检验）
CREATE TABLE IF NOT EXISTS scoring_rule (
    rule_id              VARCHAR(64) NOT NULL,
    version              INT NOT NULL,
    title                VARCHAR(128) NOT NULL,
    target_field         VARCHAR(64) NOT NULL,        -- 规则消费的市级标准字段
    rule_type            VARCHAR(16) NOT NULL,        -- ENUM_BRANCH / NUMERIC_BRANCH
    branches_json        JSONB NOT NULL,              -- [{"op":">=","value":20,"points":30,"label":"…"}]
    default_points       INT NOT NULL DEFAULT 0,
    monotonic_direction  VARCHAR(16) NOT NULL,        -- NONE / ASC / DESC：已声明安全单调方向
    status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / SUPERSEDED
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, version)
);

-- 已声明安全单调关系：字段沿 direction 变化时，得分/等级允许的变化方向
CREATE TABLE IF NOT EXISTS monotonic_declaration (
    declaration_id  VARCHAR(64) PRIMARY KEY,
    field_id        VARCHAR(64) NOT NULL,
    direction       VARCHAR(16) NOT NULL,             -- ASC / DESC
    scope           VARCHAR(16) NOT NULL,             -- SCORE / GRADE
    enum_order_json JSONB,                            -- 枚举字段的有序取值（数值字段为 NULL）
    note            TEXT NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE
);

-- 解释/稳定性报告留痕（审计：谁在何时对哪个场所做了何种解释计算）
CREATE TABLE IF NOT EXISTS explain_report (
    report_id    BIGSERIAL PRIMARY KEY,
    username     VARCHAR(64) NOT NULL,
    case_id      VARCHAR(64),
    method       VARCHAR(32) NOT NULL,
    request_json JSONB NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
