package com.fire.fieldgov.explain;

import com.fire.fieldgov.catalog.CatalogService;
import com.fire.fieldgov.model.FieldDef;
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
 * 单调性检验：检测规则更新与字段版本漂移是否违反“已声明安全单调关系”。
 * 三类检验：
 *  A. 规则结构扫描：分支得分沿声明方向必须单调（数值域全扫；枚举按声明 enum_order 扫描）；
 *  B. 规则更新候选校验：新版本规则提交前先做 A 检验，违例即拒绝发布；
 *  C. 字段版本漂移实证：场所实际取值沿声明方向扰动，确认得分不反向；漂移字段只警示、不回算历史。
 */
@Service
public class MonotonicityService {

    private final RuleRepository rules;
    private final VenueRiskService venueRisk;
    private final ExplainGraphService graph;
    private final CatalogService catalog;

    public MonotonicityService(RuleRepository rules, VenueRiskService venueRisk,
                               ExplainGraphService graph, CatalogService catalog) {
        this.rules = rules;
        this.venueRisk = venueRisk;
        this.graph = graph;
        this.catalog = catalog;
    }

    public record Violation(String level, String code, String target, String message, Map<String, Object> detail) {}

    /** A. 全量规则结构扫描。 */
    public Map<String, Object> sweepRules() {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();
        for (ScoringRule rule : rules.activeRules()) {
            Map<String, Object> r = sweepOne(rule);
            results.add(r);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> vios = (List<Map<String, Object>>) r.get("violations");
            for (Map<String, Object> v : vios) {
                violations.add(new Violation("RULE", "MONOTONIC_BREACH", rule.targetField(),
                        String.valueOf(v.get("message")), v));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("declarations", rules.monotonicDeclarations());
        out.put("ruleChecks", results);
        out.put("violationCount", violations.size());
        out.put("pass", violations.isEmpty());
        graph.audit("MONOTONIC_SWEEP", null, Map.of("violationCount", violations.size()));
        return out;
    }

    private Map<String, Object> sweepOne(ScoringRule rule) {
        List<Map<String, Object>> violations = new ArrayList<>();
        List<Map<String, Object>> trace = new ArrayList<>();
        if ("NONE".equals(rule.monotonicDirection())) {
            return Map.of("ruleId", rule.ruleId(), "ruleVersion", rule.version(),
                    "targetField", rule.targetField(), "monotonicDirection", "NONE",
                    "skipped", true, "violations", List.of(), "trace", List.of());
        }
        boolean asc = "ASC".equals(rule.monotonicDirection());
        if (rule.numeric()) {
            // 收集域端点 + 全部分支边界，逐点算分，检查相邻点得分方向
            FieldDef def = catalog.currentDefs().get(rule.targetField());
            Set<BigDecimal> points = new LinkedHashSet<>();
            if (def != null) {
                points.add(new BigDecimal(String.valueOf(def.valueDomain().getOrDefault("min", 0))));
                points.add(new BigDecimal(String.valueOf(def.valueDomain().getOrDefault("max", 0))));
            }
            for (Map<String, Object> b : rule.branches()) {
                points.add(new BigDecimal(String.valueOf(b.get("value"))));
            }
            List<BigDecimal> ordered = points.stream().sorted().toList();
            Integer prev = null;
            BigDecimal prevVal = null;
            for (BigDecimal x : ordered) {
                int pts = pointsOf(rule, x.toPlainString());
                trace.add(Map.of("fieldValue", x.toPlainString(), "points", pts));
                if (prev != null) {
                    int cmp = Integer.compare(pts, prev);
                    boolean breach = asc ? cmp < 0 : cmp > 0;
                    if (breach) {
                        violations.add(Map.of("from", prevVal.toPlainString(), "to", x.toPlainString(),
                                "pointsFrom", prev, "pointsTo", pts,
                                "message", "数值从 " + prevVal + "→" + x + " 时得分 " + prev + "→" + pts
                                        + "，违反声明的 " + rule.monotonicDirection() + " 单调关系"));
                    }
                }
                prev = pts;
                prevVal = x;
            }
        } else {
            List<String> order = enumOrder(rule.targetField());
            Integer prev = null;
            String prevVal = null;
            for (String val : order) {
                int pts = pointsOf(rule, val);
                trace.add(Map.of("fieldValue", val, "points", pts));
                if (prev != null) {
                    int cmp = Integer.compare(pts, prev);
                    boolean breach = asc ? cmp < 0 : cmp > 0;
                    if (breach) {
                        violations.add(Map.of("from", prevVal, "to", val,
                                "pointsFrom", prev, "pointsTo", pts,
                                "message", "枚举沿声明序 " + prevVal + "→" + val + " 时得分 " + prev + "→" + pts
                                        + "，违反声明的 " + rule.monotonicDirection() + " 单调关系"));
                    }
                }
                prev = pts;
                prevVal = val;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", rule.ruleId());
        out.put("ruleVersion", rule.version());
        out.put("targetField", rule.targetField());
        out.put("monotonicDirection", rule.monotonicDirection());
        out.put("skipped", false);
        out.put("trace", trace);
        out.put("violations", violations);
        out.put("pass", violations.isEmpty());
        return out;
    }

    private List<String> enumOrder(String fieldId) {
        return rules.monotonicDeclarations().stream()
                .filter(d -> fieldId.equals(d.get("field_id")))
                .map(d -> d.get("enum_order_json"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(o -> {
                    @SuppressWarnings("unchecked")
                    List<String> l = (List<String>) o;
                    return l;
                })
                .orElseGet(() -> {
                    FieldDef def = catalog.currentDefs().get(fieldId);
                    if (def == null) return List.of();
                    @SuppressWarnings("unchecked")
                    List<String> vals = (List<String>) def.valueDomain().getOrDefault("values", List.of());
                    return vals;
                });
    }

    private int pointsOf(ScoringRule rule, String actual) {
        for (Map<String, Object> b : rule.branches()) {
            String op = String.valueOf(b.get("op"));
            if (rule.numeric()) {
                int cmp = new BigDecimal(actual).compareTo(new BigDecimal(String.valueOf(b.get("value"))));
                boolean hit = switch (op) {
                    case "=" -> cmp == 0;
                    case "!=" -> cmp != 0;
                    case ">" -> cmp > 0;
                    case ">=" -> cmp >= 0;
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    default -> false;
                };
                if (hit) return ((Number) b.get("points")).intValue();
            } else if ("=".equals(op)) {
                Object v = b.get("value");
                List<?> vals = v instanceof List<?> l ? l : List.of(String.valueOf(v));
                if (vals.contains(actual)) return ((Number) b.get("points")).intValue();
            }
        }
        return rule.defaultPoints();
    }

    /** B. 规则更新候选校验：在不落库的前提下扫描候选分支。 */
    public Map<String, Object> checkRuleUpdate(String ruleId, int candidateVersion, String targetField,
                                               String ruleType, List<Map<String, Object>> branches,
                                               int defaultPoints, String monotonicDirection) {
        ScoringRule candidate = new ScoringRule(ruleId, candidateVersion, "候选更新（未落库）",
                targetField, ruleType, branches, defaultPoints, monotonicDirection, "DRAFT");
        Map<String, Object> sweep = sweepOne(candidate);
        @SuppressWarnings("unchecked")
        boolean pass = ((List<?>) sweep.get("violations")).isEmpty();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", ruleId);
        out.put("candidateVersion", candidateVersion);
        out.put("targetField", targetField);
        out.put("decision", pass ? "ACCEPTABLE" : "REJECTED_MONOTONIC_BREACH");
        out.put("sweep", sweep);
        out.put("note", pass ? "候选规则不违反已声明安全单调关系，可进入评审发布"
                : "候选规则违反单调声明，禁止发布；请调整分支得分或先履行单调关系变更治理");
        graph.audit("MONOTONIC_RULE_UPDATE_CHECK", ruleId,
                Map.of("decision", out.get("decision"), "candidateVersion", candidateVersion));
        return out;
    }

    /** C. 字段版本漂移 + 实证扰动检验。 */
    public Map<String, Object> driftCheck(LocalDate asOf, Integer catalogVersion, String purpose) {
        List<VenueRiskService.Venue> venues = venueRisk.assemble(asOf, catalogVersion, purpose);
        List<Map<String, Object>> driftedFields = new ArrayList<>();
        List<Map<String, Object>> empirical = new ArrayList<>();
        for (VenueRiskService.Venue v : venues) {
            for (VenueRiskService.Provenance p : v.provenance().values()) {
                if (!p.versionDrift()) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("caseId", v.caseId());
                d.put("sourceField", p.sourceField());
                d.put("fieldDefVersionAtSubmit", p.fieldDefVersionAtSubmit());
                d.put("fieldStatusAtSubmit", p.fieldStatusAtSubmit());
                d.put("currentFieldDefVersion", p.currentFieldDefVersion());
                d.put("fieldStatusNow", p.fieldStatusNow());
                d.put("policy", "历史表单按提交时 v" + v.instance().catalogVersion()
                        + " 语义解释；现行含义不得回算（REFUSED 守卫见替换情景）");
                driftedFields.add(d);
            }
            // 实证：对每个有声明方向的在场规则，用可达取值沿方向扰动，确认总分不反向
            for (ScoringRule rule : rules.activeRules()) {
                if ("NONE".equals(rule.monotonicDirection())) continue;
                String stdField = rule.targetField();
                String actual = v.stdValues().get(stdField);
                if (actual == null) continue;
                Map<String, Object> probe = empiricalProbe(v, rule, actual);
                if (probe != null) empirical.add(probe);
            }
        }
        long breaches = empirical.stream().filter(m -> Boolean.FALSE.equals(m.get("pass"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", String.valueOf(asOf));
        out.put("driftedFieldUses", driftedFields);
        out.put("empiricalProbes", empirical);
        out.put("empiricalBreachCount", breaches);
        out.put("pass", breaches == 0);
        out.put("note", "漂移字段仅警示不回算；实证扰动基于已批准映射可达取值，沿声明方向检验得分单调性");
        graph.audit("MONOTONIC_DRIFT", null, Map.of(
                "driftedFieldUses", driftedFields.size(), "empiricalBreachCount", breaches));
        return out;
    }

    private Map<String, Object> empiricalProbe(VenueRiskService.Venue v, ScoringRule rule, String actual) {
        List<String> order = enumOrder(rule.targetField());
        boolean asc = "ASC".equals(rule.monotonicDirection());
        List<String> reachable = new ArrayList<>();
        if (rule.numeric()) reachable = List.of(actual); // 数值字段的全扫在 sweepRules 完成，此处只做枚举实证
        else {
            for (String val : order) {
                boolean reach = catalog.allContracts().stream()
                        .filter(c -> c.targetFieldId().equals(rule.targetField()))
                        .filter(c -> "APPROVED".equals(c.status()) || "SUPERSEDED".equals(c.status()))
                        .flatMap(c -> c.values().stream())
                        .filter(com.fire.fieldgov.model.MappingValue::equivalent)
                        .anyMatch(mv -> val.equals(mv.targetValue()));
                if (reach) reachable.add(val);
            }
        }
        if (reachable.size() < 2) return null;
        int idx = reachable.indexOf(actual);
        if (idx < 0) return null;
        List<Map<String, Object>> trace = new ArrayList<>();
        Integer prevScore = null;
        boolean pass = true;
        for (String val : reachable) {
            Map<String, String> trial = new LinkedHashMap<>(v.stdValues());
            trial.put(rule.targetField(), val);
            int score = venueRisk.totalScore(venueRisk.evaluate(trial, v.missing()));
            trace.add(Map.of("value", val, "totalScore", score));
            if (prevScore != null) {
                int cmp = Integer.compare(score, prevScore);
                if (asc ? cmp < 0 : cmp > 0) pass = false;
            }
            prevScore = score;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", v.caseId());
        out.put("ruleId", rule.ruleId());
        out.put("targetField", rule.targetField());
        out.put("direction", rule.monotonicDirection());
        out.put("reachableOrder", reachable);
        out.put("trace", trace);
        out.put("pass", pass);
        return out;
    }
}
