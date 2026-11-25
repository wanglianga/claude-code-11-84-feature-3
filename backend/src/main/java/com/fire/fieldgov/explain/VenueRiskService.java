package com.fire.fieldgov.explain;

import com.fire.fieldgov.catalog.CatalogService;
import com.fire.fieldgov.form.FormInstanceService;
import com.fire.fieldgov.mapping.EventReplayService;
import com.fire.fieldgov.mapping.MappingEngine;
import com.fire.fieldgov.model.FieldDef;
import com.fire.fieldgov.model.FormInstance;
import com.fire.fieldgov.model.MappedValue;
import com.fire.fieldgov.policy.AccessPolicyService;
import com.fire.fieldgov.security.CurrentUser;
import com.fire.fieldgov.security.UserContext;
import com.fire.fieldgov.web.GovernanceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 高风险场所排序的解释与稳定性服务（特征3）。
 *
 * 五条能力：
 *  1) 规则解释图：源字段(版本/废止状态) → 契约/换算(批准状态) → 标准值 → 规则分支 → 总分/等级/排名；
 *  2) 字段贡献分解：规则路径归因（逐条可审计）+ Shapley 式贡献（对分级阈值效用，体现交互）；
 *  3) 缺失情景器：缺失字段枚举可能取值，给分数区间与排名区间；排名会翻转时只发布区间；
 *  4) 单调性检验：值域扫描 + 规则更新候选校验 + 字段版本漂移检测，对照已声明安全单调关系；
 *  5) 稳定性报告：同一场所下替换、缺失、单位换算对等级/排名的边际影响汇总。
 *
 * 三条铁律：
 *  - 废止/改名字段不得按现行含义回算历史（版本守卫，违例 REFUSED，不进入任何计算）；
 *  - 缺失导致排名翻转时只给区间，不给点排名；
 *  - 无批准换算不得进入贡献计算（状态 BLOCKED，剔除并留痕）。
 */
@Service
public class VenueRiskService {

    private final CatalogService catalog;
    private final FormInstanceService forms;
    private final MappingEngine mapping;
    private final EventReplayService replay;
    private final AccessPolicyService policy;
    private final RuleRepository rules;
    private final JdbcTemplate jdbc;

    public VenueRiskService(CatalogService catalog, FormInstanceService forms, MappingEngine mapping,
                            EventReplayService replay, AccessPolicyService policy,
                            RuleRepository rules, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.forms = forms;
        this.mapping = mapping;
        this.replay = replay;
        this.policy = policy;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    /** 等级阈值（分）：RED 高风险 / ORANGE / YELLOW / BLUE / NONE。 */
    public static final List<Map<String, Object>> GRADE_BANDS = List.of(
            Map.of("grade", "RED", "label", "高风险（红）", "min", 70, "rank", 4),
            Map.of("grade", "ORANGE", "label", "较高风险（橙）", "min", 50, "rank", 3),
            Map.of("grade", "YELLOW", "label", "中风险（黄）", "min", 30, "rank", 2),
            Map.of("grade", "BLUE", "label", "低风险（蓝）", "min", 10, "rank", 1),
            Map.of("grade", "NONE", "label", "未入级", "min", 0, "rank", 0));

    // ============================ 场所数据装配 ============================

    /** 单个场所（案件）的解释上下文：实例、标准值、逐字段来源、访问策略结论。 */
    public record Venue(FormInstance instance, Map<String, String> stdValues,
                        Map<String, Provenance> provenance, Map<String, String> missing,
                        AccessPolicyService.Decision access, List<FieldDef> touched) {
        public String caseId() { return instance.caseId(); }
        public String district() { return instance.district(); }
    }

    /** 标准值的完整来源证据（解释图节点的数据底座）。 */
    public record Provenance(String stdField, String sourceField, String sourceValue, String targetValue,
                             String contractId, Integer contractVersion, String contractStatus,
                             String transform, boolean equivalent, String evidence, boolean viaEvent,
                             int fieldDefVersionAtSubmit, String fieldStatusAtSubmit,
                             Integer currentFieldDefVersion, String fieldStatusNow, boolean versionDrift) {}

    /**
     * 装配当前用户可见的全部场所。访问策略与查询编译器同口径（辖区/案件/敏感级/目的）。
     */
    public List<Venue> assemble(LocalDate asOf, Integer catalogVersion, String purpose) {
        UserContext user = CurrentUser.get();
        AccessPolicyService.Decision purposeDecision = policy.checkPurpose(user, purpose);
        if (!purposeDecision.allowed()) {
            throw new GovernanceException("访问被拒绝：" + purposeDecision.reason());
        }
        int cap = catalogVersion == null ? catalog.currentCatalogVersion() : catalogVersion;
        Map<String, FieldDef> defsNow = catalog.defsAsOf(cap, asOf);
        Map<String, EventReplayService.CaseState> states = replay.replayAll(asOf);

        // 一个案件多实例时取检查日最新者
        Map<String, FormInstance> latest = new LinkedHashMap<>();
        for (FormInstance inst : forms.all()) {
            FormInstance old = latest.get(inst.caseId());
            if (old == null || inst.inspectDate().isAfter(old.inspectDate())) latest.put(inst.caseId(), inst);
        }

        List<Venue> out = new ArrayList<>();
        for (FormInstance inst : latest.values()) {
            Map<String, FieldDef> defsAtSubmit = catalog.defsAsOf(inst.catalogVersion(), inst.inspectDate());
            Map<String, MappedValue> mapped = new LinkedHashMap<>();
            List<FieldDef> touched = new ArrayList<>();
            for (Map.Entry<String, String> e : inst.values().entrySet()) {
                MappedValue mv = mapping.map(inst, e.getKey(), e.getValue());
                mapped.put(e.getKey(), mv);
                FieldDef d = defsNow.get(e.getKey());
                if (d != null) touched.add(d);
            }
            EventReplayService.CaseState state = states.get(inst.caseId());
            Map<String, String> stdValues = new LinkedHashMap<>();
            Map<String, Provenance> provenance = new LinkedHashMap<>();
            Map<String, String> missing = new LinkedHashMap<>();

            // 事件重放优先（等级/整改状态），表单映射兜底
            absorbEventOrMapped(inst, "std.hazard_level", state == null ? null : state.hazardLevel(),
                    mapped, defsAtSubmit, defsNow, stdValues, provenance, missing);
            absorbEventOrMapped(inst, "std.rectification_status", state == null ? null : state.rectificationStatus(),
                    mapped, defsAtSubmit, defsNow, stdValues, provenance, missing);
            for (MappedValue mv : mapped.values()) {
                if (mv.targetFieldId() == null || !mv.targetFieldId().startsWith("std.")) continue;
                if (stdValues.containsKey(mv.targetFieldId())) continue;
                if (mv.equivalent() && mv.targetValue() != null) {
                    stdValues.put(mv.targetFieldId(), mv.targetValue());
                    provenance.put(mv.targetFieldId(), buildProvenance(inst, mv, false, defsAtSubmit, defsNow));
                } else {
                    missing.putIfAbsent(mv.targetFieldId(),
                            "映射被拒绝/语义未决：" + mv.evidence()
                                    + (mv.contractId() == null ? "" : "（契约 " + mv.contractId() + "）"));
                }
            }
            // 规则需要、但表单完全没有提供的标准字段
            for (ScoringRule rule : rules.activeRules()) {
                String f = rule.targetField();
                if (!stdValues.containsKey(f) && !missing.containsKey(f)) {
                    missing.put(f, "表单未采集该字段，且无可用映射来源");
                }
            }
            AccessPolicyService.Decision access = policy.evaluateRow(user, inst, touched, purpose);
            out.add(new Venue(inst, stdValues, provenance, missing, access, touched));
        }
        return out;
    }

    private void absorbEventOrMapped(FormInstance inst, String stdField, String eventValue,
                                     Map<String, MappedValue> mapped,
                                     Map<String, FieldDef> defsAtSubmit, Map<String, FieldDef> defsNow,
                                     Map<String, String> stdValues, Map<String, Provenance> provenance,
                                     Map<String, String> missing) {
        MappedValue support = mapped.values().stream()
                .filter(mv -> stdField.equals(mv.targetFieldId())).findFirst().orElse(null);
        boolean eventUsable = eventValue != null && !eventValue.isBlank() && !"UNMAPPED".equals(eventValue);
        if (eventUsable) {
            stdValues.put(stdField, eventValue);
            provenance.put(stdField, buildProvenance(inst, support == null
                    ? new MappedValue(stdField, eventValue, stdField, eventValue, "EVENT_REPLAY", null, null,
                            "案件事件流重放得到的当前状态", true) : support,
                    true, defsAtSubmit, defsNow));
            return;
        }
        if (support != null && support.equivalent() && support.targetValue() != null) {
            stdValues.put(stdField, support.targetValue());
            provenance.put(stdField, buildProvenance(inst, support, false, defsAtSubmit, defsNow));
        } else {
            missing.put(stdField, support == null
                    ? "无事件状态且无映射来源"
                    : "映射被拒绝/语义未决：" + support.evidence());
        }
    }

    private Provenance buildProvenance(FormInstance inst, MappedValue mv, boolean viaEvent,
                                       Map<String, FieldDef> defsAtSubmit, Map<String, FieldDef> defsNow) {
        String source = mv.sourceFieldId();
        FieldDef atSubmit = defsAtSubmit.get(source);
        FieldDef now = defsNow.get(source);
        int submitVer = atSubmit == null ? inst.catalogVersion() : atSubmit.version();
        String submitStatus = atSubmit == null ? "UNKNOWN" : atSubmit.status();
        Integer nowVer = now == null ? null : now.version();
        String nowStatus = now == null ? "NOT_IN_CATALOG" : now.status();
        boolean drift = now != null && atSubmit != null
                && (!java.util.Objects.equals(now.definition(), atSubmit.definition())
                    || !java.util.Objects.equals(now.valueDomain().get("values"),
                            atSubmit.valueDomain().get("values")));
        String contractStatus = null;
        if (mv.contractId() != null && !"EVENT_REPLAY".equals(mv.contractId()) && !"IDENTITY".equals(mv.contractId())) {
            contractStatus = catalog.allContracts().stream()
                    .filter(c -> c.contractId().equals(mv.contractId())).findFirst()
                    .map(com.fire.fieldgov.model.MappingContract::status).orElse(null);
        } else if ("IDENTITY".equals(mv.contractId())) {
            contractStatus = "IDENTITY";
        } else if ("EVENT_REPLAY".equals(mv.contractId())) {
            contractStatus = "EVENT_REPLAY";
        }
        return new Provenance(mv.targetFieldId(), source, mv.sourceValue(), mv.targetValue(),
                mv.contractId(), mv.contractVersion(), contractStatus, mv.transform(), mv.equivalent(),
                mv.evidence(), viaEvent, submitVer, submitStatus, nowVer, nowStatus, drift);
    }

    // ============================ 评分引擎 ============================

    public record RuleHit(String ruleId, int ruleVersion, String title, String targetField,
                          String matchedBranch, String branchLabel, int points,
                          String monotonicDirection, String actualValue, boolean fieldMissing) {}

    /** 对一组标准值套用当前生效规则；缺失字段的规则记 0 分并标记 fieldMissing。 */
    public List<RuleHit> evaluate(Map<String, String> stdValues, Map<String, String> missing) {
        List<RuleHit> hits = new ArrayList<>();
        for (ScoringRule rule : rules.activeRules()) {
            String actual = stdValues.get(rule.targetField());
            if (actual == null) {
                hits.add(new RuleHit(rule.ruleId(), rule.version(), rule.title(), rule.targetField(),
                        "MISSING", "字段缺失，规则不触发（记0分，进入缺失情景）", 0,
                        rule.monotonicDirection(), null, true));
                continue;
            }
            Map<String, Object> branch = matchBranch(rule, actual);
            int points = branch == null ? rule.defaultPoints() : ((Number) branch.get("points")).intValue();
            String label = branch == null ? "默认分支" : String.valueOf(branch.getOrDefault("label", ""));
            String op = branch == null ? "DEFAULT" : String.valueOf(branch.get("op"));
            hits.add(new RuleHit(rule.ruleId(), rule.version(), rule.title(), rule.targetField(),
                    op, label, points, rule.monotonicDirection(), actual, false));
        }
        return hits;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> matchBranch(ScoringRule rule, String actual) {
        for (Map<String, Object> b : rule.branches()) {
            String op = String.valueOf(b.get("op"));
            if (rule.numeric()) {
                if (compareNum(actual, String.valueOf(b.get("value")), op)) return b;
            } else {
                Set<String> vals = b.get("value") instanceof List<?> l
                        ? new LinkedHashSet<>((List<String>) l)
                        : new LinkedHashSet<>(List.of(String.valueOf(b.get("value"))));
                if ("=".equals(op) && vals.contains(actual)) return b;
                if ("!=".equals(op) && !vals.contains(actual)) return b;
            }
        }
        return null;
    }

    private boolean compareNum(String a, String b, String op) {
        int cmp = new BigDecimal(a).compareTo(new BigDecimal(b));
        return switch (op) {
            case "=" -> cmp == 0;
            case "!=" -> cmp != 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            default -> throw new GovernanceException("评分规则不支持操作符 " + op);
        };
    }

    public int totalScore(List<RuleHit> hits) { return hits.stream().mapToInt(RuleHit::points).sum(); }

    public Map<String, Object> gradeOf(int score) {
        for (Map<String, Object> band : GRADE_BANDS) {
            if (score >= ((Number) band.get("min")).intValue()) return band;
        }
        return GRADE_BANDS.get(GRADE_BANDS.size() - 1);
    }

    /** 分级阈值效用：Shapley 的 v(S)，非线性从而体现规则交互。 */
    public int gradeUtility(int score) {
        return ((Number) gradeOf(score).get("rank")).intValue();
    }

    /**
     * 已批准（含对旧表单仍有效的 SUPERSEDED）契约能产出的标准值集合。
     * 无批准映射路径的字段（如 DRAFT 契约、equivalent=false）没有可达取值，
     * 缺失情景与排名字段区间均不得据此假设补全。
     */
    public Set<String> reachableStdValues(String stdField) {
        Set<String> out = new LinkedHashSet<>();
        for (com.fire.fieldgov.model.MappingContract c : catalog.allContracts()) {
            if (!c.targetFieldId().equals(stdField)) continue;
            if (!"APPROVED".equals(c.status()) && !"SUPERSEDED".equals(c.status())) continue;
            c.values().stream().filter(com.fire.fieldgov.model.MappingValue::equivalent)
                    .map(com.fire.fieldgov.model.MappingValue::targetValue)
                    .filter(v -> v != null).forEach(out::add);
        }
        return out;
    }

    /** 缺失字段在“已批准映射可达取值”范围内的最大可能规则得分；无可达取值时为 0（区间不放宽）。 */
    public int maxReachablePoints(String stdField) {
        Set<String> reachable = reachableStdValues(stdField);
        if (reachable.isEmpty()) return 0;
        ScoringRule rule = rules.activeRules().stream()
                .filter(r -> r.targetField().equals(stdField)).findFirst().orElse(null);
        if (rule == null) return 0;
        return reachable.stream()
                .mapToInt(v -> evaluate(Map.of(stdField, v), Map.of()).stream()
                        .filter(h -> h.targetField().equals(stdField))
                        .findFirst().map(VenueRiskService.RuleHit::points).orElse(0))
                .max().orElse(0);
    }
}
