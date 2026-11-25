package com.fire.fieldgov.query;

import com.fire.fieldgov.catalog.CatalogService;
import com.fire.fieldgov.db.DbJson;
import com.fire.fieldgov.form.FormInstanceService;
import com.fire.fieldgov.mapping.EventReplayService;
import com.fire.fieldgov.mapping.MappingEngine;
import com.fire.fieldgov.model.FieldDef;
import com.fire.fieldgov.model.FormInstance;
import com.fire.fieldgov.model.MappedValue;
import com.fire.fieldgov.policy.AccessPolicyService;
import com.fire.fieldgov.policy.SuppressionService;
import com.fire.fieldgov.security.CurrentUser;
import com.fire.fieldgov.security.UserContext;
import com.fire.fieldgov.web.GovernanceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询编译器：把“标准化表达”的查询编译为受治理的聚合结果。
 * 编译流水线：
 *   目的校验 → 版本/时点固定 → 标准字段校验 → 逐实例访问策略 → 逐值映射（证据/换算/生效范围）
 *   → 事件状态重放 → 标准化过滤 → 分组计数 → 最小发布组与互补抑制 → 版本快照与审计落库。
 */
@Service
public class QueryCompiler {

    private final CatalogService catalog;
    private final FormInstanceService forms;
    private final MappingEngine mapping;
    private final EventReplayService replay;
    private final AccessPolicyService policy;
    private final SuppressionService suppression;
    private final JdbcTemplate jdbc;

    public QueryCompiler(CatalogService catalog, FormInstanceService forms, MappingEngine mapping,
                         EventReplayService replay, AccessPolicyService policy,
                         SuppressionService suppression, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.forms = forms;
        this.mapping = mapping;
        this.replay = replay;
        this.policy = policy;
        this.suppression = suppression;
        this.jdbc = jdbc;
    }

    public Map<String, Object> compile(QueryRequest req) {
        UserContext user = CurrentUser.get();
        String purpose = req.purpose() == null ? "STAT" : req.purpose();
        AccessPolicyService.Decision purposeDecision = policy.checkPurpose(user, purpose);
        if (!purposeDecision.allowed()) {
            throw new GovernanceException("访问被拒绝：" + purposeDecision.reason());
        }
        LocalDate asOf = req.asOf() == null || req.asOf().isBlank() ? LocalDate.now() : LocalDate.parse(req.asOf());
        int catalogVersion = req.catalogVersion() == null ? catalog.currentCatalogVersion() : req.catalogVersion();
        Map<String, FieldDef> defs = catalog.defsAsOf(catalogVersion, asOf);

        // 1) 标准化表达校验：过滤字段必须是市级标准字段且在该版本存在
        List<QueryRequest.Filter> filters = req.filters() == null ? List.of() : req.filters();
        for (QueryRequest.Filter f : filters) {
            FieldDef d = defs.get(f.field());
            if (d == null) {
                throw new GovernanceException("查询字段 " + f.field() + " 在目录 v" + catalogVersion
                        + " 不存在（不得使用未入目录的辖区私有字段发起市级查询）");
            }
            if (!f.field().startsWith("std.")) {
                throw new GovernanceException("查询必须使用市级标准字段（std.*）表达；" + f.field() + " 是辖区私有字段");
            }
        }

        // 2) 事件状态重放（asOf 时点）
        Map<String, EventReplayService.CaseState> states = replay.replayAll(asOf);
        List<String> groupBy = req.groupBy() == null || req.groupBy().isEmpty() ? List.of("district") : req.groupBy();

        List<Map<String, Object>> diagnostics = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> rawCounts = new LinkedHashMap<>();
        List<String> includedCases = new ArrayList<>();

        for (FormInstance inst : forms.all()) {
            Map<String, Object> diag = new LinkedHashMap<>();
            diag.put("instanceId", inst.instanceId());
            diag.put("caseId", inst.caseId());
            diag.put("district", inst.district());
            diag.put("catalogVersion", inst.catalogVersion());
            diag.put("inspectDate", inst.inspectDate().toString());
            List<String> reasons = new ArrayList<>();

            // 时点与辖区
            if (inst.inspectDate().isAfter(asOf)) {
                reasons.add("检查日期晚于 asOf，时点外剔除");
            }
            if (req.districts() != null && !req.districts().isEmpty() && !req.districts().contains(inst.district())) {
                reasons.add("不在查询辖区范围");
            }

            // 访问策略（按实例实际触达的字段定义判定敏感级）
            List<FieldDef> touched = inst.values().keySet().stream()
                    .map(defs::get).filter(d -> d != null).toList();
            AccessPolicyService.Decision access = policy.evaluateRow(user, inst, touched, purpose);
            if (!access.allowed()) {
                reasons.add("访问策略拒绝：" + access.reason());
            }

            // 3) 逐值映射（全部字段都留下证据链；查询所需字段失败则剔除）
            Map<String, MappedValue> mapped = new LinkedHashMap<>();
            List<Map<String, Object>> traces = new ArrayList<>();
            for (Map.Entry<String, String> e : inst.values().entrySet()) {
                MappedValue mv = mapping.map(inst, e.getKey(), e.getValue());
                mapped.put(mv.targetFieldId() == null ? e.getKey() : mv.targetFieldId(), mv);
                traces.add(Map.of(
                        "sourceField", e.getKey(), "sourceValue", e.getValue(),
                        "targetField", mv.targetFieldId() == null ? "" : mv.targetFieldId(),
                        "targetValue", mv.targetValue() == null ? "" : mv.targetValue(),
                        "contractId", mv.contractId() == null ? "" : mv.contractId(),
                        "contractVersion", mv.contractVersion() == null ? "" : mv.contractVersion(),
                        "transform", mv.transform() == null ? "" : mv.transform(),
                        "equivalent", mv.equivalent(),
                        "evidence", mv.evidence()));
                if (!mv.equivalent()) {
                    warnings.add(inst.caseId() + " 的 " + e.getKey() + "=" + e.getValue() + " 无法证明语义等价，已拒绝进入正式统计");
                }
            }
            diag.put("mappings", traces);

            // 4) 事件状态（重放优先，表单映射兜底）
            EventReplayService.CaseState state = states.get(inst.caseId());
            String hazardLevel = state != null ? state.hazardLevel() : null;
            String rectStatus = state != null ? state.rectificationStatus() : null;
            if (hazardLevel == null && mapped.get("std.hazard_level") != null) {
                hazardLevel = mapped.get("std.hazard_level").targetValue();
            }
            if (rectStatus == null && mapped.get("std.rectification_status") != null) {
                rectStatus = mapped.get("std.rectification_status").targetValue();
            }
            Map<String, String> stdValues = new LinkedHashMap<>();
            stdValues.put("std.hazard_level", hazardLevel);
            stdValues.put("std.rectification_status", rectStatus);
            for (Map.Entry<String, MappedValue> e : mapped.entrySet()) {
                if (e.getKey().startsWith("std.") && e.getValue().equivalent() && e.getValue().targetValue() != null) {
                    stdValues.putIfAbsent(e.getKey(), e.getValue().targetValue());
                }
            }
            diag.put("eventState", Map.of(
                    "hazardLevel", hazardLevel == null ? "" : hazardLevel,
                    "rectificationStatus", rectStatus == null ? "" : rectStatus,
                    "replayedEvents", state == null ? 0 : state.appliedEvents().size()));

            // 5) 标准化过滤
            for (QueryRequest.Filter f : filters) {
                String actual = stdValues.get(f.field());
                if (actual == null) {
                    reasons.add("过滤字段 " + f.field() + " 无可用标准值（映射被拒绝或语义未决），不得强行合并");
                    continue;
                }
                if (!match(f, actual, defs.get(f.field()))) {
                    reasons.add("标准值 " + f.field() + "=" + actual + " 不满足 " + f.op() + " " + f.value());
                }
            }

            diag.put("included", reasons.isEmpty());
            diag.put("reasons", reasons);
            diagnostics.add(diag);
            if (reasons.isEmpty()) {
                includedCases.add(inst.caseId());
                String key = groupKey(inst, stdValues, groupBy);
                rawCounts.merge(key, 1, Integer::sum);
            }
        }

        // 6) 披露抑制
        boolean includeTotal = req.includeTotal() == null || req.includeTotal();
        SuppressionService.DisclosureResult disclosure = suppression.apply(rawCounts, includeTotal);
        // 转为普通 Map，便于历史复现时按键读取（record 无法直接转 Map）
        Map<String, Object> disclosureMap = new LinkedHashMap<>();
        disclosureMap.put("groups", disclosure.groups());
        disclosureMap.put("total", disclosure.total());
        disclosureMap.put("totalSuppressed", disclosure.totalSuppressed());
        disclosureMap.put("totalReason", disclosure.totalReason());
        disclosureMap.put("rawTotal", disclosure.rawTotal());
        disclosureMap.put("publishedGroupCount", disclosure.publishedGroupCount());
        disclosureMap.put("suppressedGroupCount", disclosure.suppressedGroupCount());
        disclosureMap.put("threshold", disclosure.threshold());

        // 7) 版本快照（历史复现依据）
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("catalogVersion", catalogVersion);
        snapshot.put("asOf", asOf.toString());
        snapshot.put("contracts", catalog.allContracts().stream()
                .filter(c -> c.version() <= catalogVersion)
                .map(c -> c.contractId() + ":" + c.version() + ":" + c.status()).sorted().toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asOf", asOf.toString());
        result.put("catalogVersion", catalogVersion);
        result.put("purpose", purpose);
        result.put("groupBy", groupBy);
        result.put("disclosure", disclosureMap);
        result.put("includedCases", includedCases);
        result.put("caseDiagnostics", diagnostics);
        result.put("compileWarnings", warnings);
        result.put("versionSnapshot", snapshot);
        result.put("suppressionThreshold", suppression.getThreshold());

        long queryId = persistHistory(user.username(), purpose, req, result, snapshot);
        result.put("queryId", queryId);
        jdbc.update("INSERT INTO audit_log(username,action,detail) VALUES (?,?,?::jsonb)",
                user.username(), "RUN_QUERY",
                DbJson.json(Map.of("queryId", queryId, "purpose", purpose,
                        "groups", rawCounts.keySet(), "included", includedCases.size())));
        return result;
    }

    private boolean match(QueryRequest.Filter f, String actual, FieldDef def) {
        String type = def.valueDomain() != null && def.valueDomain().get("type") != null
                ? def.valueDomain().get("type").toString() : "enum";
        String expected = String.valueOf(f.value());
        if ("numeric".equals(type)) {
            BigDecimal a = new BigDecimal(actual);
            BigDecimal b = new BigDecimal(expected);
            int cmp = a.compareTo(b);
            return switch (f.op()) {
                case "=" -> cmp == 0;
                case "!=" -> cmp != 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                default -> throw new GovernanceException("数值字段不支持操作符 " + f.op());
            };
        }
        if (!"=".equals(f.op()) && !"!=".equals(f.op())) {
            throw new GovernanceException("枚举字段仅支持 = / !=，收到 " + f.op());
        }
        return "=".equals(f.op()) ? actual.equals(expected) : !actual.equals(expected);
    }

    private String groupKey(FormInstance inst, Map<String, String> stdValues, List<String> groupBy) {
        if (groupBy.size() == 1 && "district".equals(groupBy.get(0))) {
            return inst.district();
        }
        List<String> parts = new ArrayList<>();
        for (String g : groupBy) {
            parts.add("district".equals(g) ? inst.district() : stdValues.getOrDefault(g, "∅"));
        }
        return String.join("|", parts);
    }

    private long persistHistory(String username, String purpose, QueryRequest req,
                                Map<String, Object> result, Map<String, Object> snapshot) {
        jdbc.update("INSERT INTO query_history(username,purpose,query_json,result_json,version_snapshot) " +
                        "VALUES (?,?,?::jsonb,?::jsonb,?::jsonb)",
                username, purpose, DbJson.json(req), DbJson.json(result), DbJson.json(snapshot));
        return jdbc.queryForObject("SELECT currval('query_history_query_id_seq')", Long.class);
    }
}
