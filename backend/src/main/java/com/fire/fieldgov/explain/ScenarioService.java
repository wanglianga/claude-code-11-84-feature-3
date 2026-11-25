package com.fire.fieldgov.explain;

import com.fire.fieldgov.catalog.CatalogService;
import com.fire.fieldgov.mapping.MappingEngine;
import com.fire.fieldgov.model.FieldDef;
import com.fire.fieldgov.model.FormInstance;
import com.fire.fieldgov.model.MappedValue;
import com.fire.fieldgov.model.MappingContract;
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
 * 缺失情景器 + 替换/单位换算边际影响 + 稳定性报告。
 *
 * 铁律落地：
 *  - 废止/改名字段：替换情景默认且只能按“表单提交时目录版本”的语义重算；请求按现行含义回算历史 → REFUSED；
 *  - 缺失使排名翻转：只发布分数/等级/名次区间（best/worst），不发布定点名次；
 *  - 无批准换算：换算情景状态 BLOCKED，不产生得分/贡献，只留阻塞原因。
 */
@Service
public class ScenarioService {

    private final VenueRiskService venueRisk;
    private final ExplainGraphService graph;
    private final CatalogService catalog;
    private final MappingEngine mapping;
    private final RuleRepository rules;

    public ScenarioService(VenueRiskService venueRisk, ExplainGraphService graph,
                           CatalogService catalog, MappingEngine mapping, RuleRepository rules) {
        this.venueRisk = venueRisk;
        this.graph = graph;
        this.catalog = catalog;
        this.mapping = mapping;
        this.rules = rules;
    }

    public record ScenarioRow(String scenario, String status, String detail,
                              Integer score, String grade, Integer rank,
                              Integer scoreDelta, Integer rankDelta, boolean rankFlip,
                              Map<String, Object> extra) {}

    // ============================ 缺失情景 ============================

    /**
     * 缺失字段情景：枚举该标准字段值域内“有已批准映射可达”的取值，
     * 逐值重算分数/等级/名次，给区间；任何取值会改变名次即标记翻转，只发布区间。
     */
    public Map<String, Object> missingScenarios(String caseId, String stdField,
                                                LocalDate asOf, Integer catalogVersion, String purpose) {
        VenueRiskService.Venue venue = graph.findVenue(caseId, asOf, catalogVersion, purpose);
        if (!venue.missing().containsKey(stdField)) {
            throw new com.fire.fieldgov.web.GovernanceException(
                    stdField + " 在该场所并非缺失字段（缺失情景器仅处理缺失/语义未决字段）");
        }
        FieldDef def = catalog.defsAsOf(catalogVersion, asOf).get(stdField);
        List<Population> pop = baselinePopulation(asOf, catalogVersion, purpose);
        int baseScore = scoreOf(venue);
        Integer baseRank = rankOf(pop, caseId, baseScore);

        List<Map<String, Object>> candidates = candidateValues(stdField, def);
        Set<String> reachable = venueRisk.reachableStdValues(stdField);
        List<ScenarioRow> rows = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String value = String.valueOf(candidate.get("value"));
            Map<String, String> trialValues = new LinkedHashMap<>(venue.stdValues());
            Map<String, String> trialMissing = new LinkedHashMap<>(venue.missing());
            if (!reachable.contains(value)) {
                rows.add(new ScenarioRow("缺失补全=" + value, "UNREACHABLE",
                        "无已批准映射可产生该标准值，不得假设补全", null, null, null, null, null, false, candidate));
                continue;
            }
            trialValues.put(stdField, value);
            trialMissing.remove(stdField);
            int score = venueRisk.totalScore(venueRisk.evaluate(trialValues, trialMissing));
            Integer rank = rankOf(pop, caseId, score);
            rows.add(new ScenarioRow("缺失补全=" + value, "COMPUTED", "按已批准映射可达取值假设补全",
                    score, String.valueOf(venueRisk.gradeOf(score).get("grade")), rank,
                    score - baseScore, delta(baseRank, rank),
                    baseRank != null && !baseRank.equals(rank), candidate));
        }
        Map<String, Object> out = assembleInterval("MISSING", caseId, stdField, venue.missing().get(stdField),
                baseScore, baseRank, rows,
                "缺失使名次可能翻转时只给区间；UNREACHABLE 取值不参与区间端点");
        graph.audit("MISSING_SCENARIO", caseId, Map.of("field", stdField, "computed",
                rows.stream().filter(r -> "COMPUTED".equals(r.status())).count()));
        return out;
    }

    /** 数值字段取域端点与规则分档边界（只有跨边界才改得分）；枚举字段取整个有序值域。 */
    private List<Map<String, Object>> candidateValues(String stdField, FieldDef def) {
        if (def == null) {
            // 字段尚未进入当前目录（如治理批准前的扩展目标）：回退到单调声明的枚举序，
            // 仍受“已批准映射可达取值”约束，无批准路径时全部 UNREACHABLE
            List<String> declared = rules.monotonicDeclarations().stream()
                    .filter(d -> stdField.equals(d.get("field_id")))
                    .map(d -> d.get("enum_order_json"))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .map(o -> {
                        @SuppressWarnings("unchecked")
                        List<String> l = (List<String>) o;
                        return l;
                    }).orElse(null);
            if (declared != null) {
                return declared.stream()
                        .map(v -> Map.<String, Object>of("value", v, "kind", "DECLARED_ENUM_ORDER"))
                        .toList();
            }
            throw new com.fire.fieldgov.web.GovernanceException(
                    stdField + " 在当前目录不存在且无单调声明枚举序，无法构造缺失情景");
        }
        boolean numeric = "numeric".equals(def.valueDomain().get("type"));
        if (!numeric) {
            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) def.valueDomain().getOrDefault("values", List.of());
            List<Map<String, Object>> out = new ArrayList<>();
            for (String v : values) out.add(Map.of("value", v, "kind", "ENUM_DOMAIN"));
            return out;
        }
        Set<BigDecimal> points = new LinkedHashSet<>();
        points.add(new BigDecimal(String.valueOf(def.valueDomain().getOrDefault("min", 0))));
        points.add(new BigDecimal(String.valueOf(def.valueDomain().getOrDefault("max", 0))));
        rules.activeRules().stream().filter(r -> r.targetField().equals(stdField))
                .flatMap(r -> r.branches().stream())
                .forEach(b -> points.add(new BigDecimal(String.valueOf(b.get("value")))));
        return points.stream().sorted()
                .map(bd -> Map.<String, Object>of("value", bd.toPlainString(), "kind", "DOMAIN_OR_RULE_BOUNDARY"))
                .toList();
    }

    // ============================ 替换情景（同版本值域） ============================

    /**
     * 同版本字段取值替换：候选值取自表单提交时目录版本的源字段值域，映射仍受提交版本契约约束。
     * 显式演示：废止字段按现行含义回算 → REFUSED；equivalent=false 取值 → REJECTED。
     */
    public Map<String, Object> substitutionScenarios(String caseId, String sourceField, boolean useCurrentMeaning,
                                                     LocalDate asOf, Integer catalogVersion, String purpose) {
        VenueRiskService.Venue venue = graph.findVenue(caseId, asOf, catalogVersion, purpose);
        FormInstance inst = venue.instance();
        String original = inst.values().get(sourceField);
        if (original == null) {
            throw new com.fire.fieldgov.web.GovernanceException("源字段 " + sourceField + " 不在该表单实例中");
        }
        FieldDef atSubmit = catalog.defsAsOf(inst.catalogVersion(), inst.inspectDate()).get(sourceField);
        FieldDef now = catalog.defsAsOf(catalogVersion, asOf).get(sourceField);
        boolean deprecated = now != null && "DEPRECATED".equals(now.status());
        boolean drifted = now != null && atSubmit != null
                && (!java.util.Objects.equals(now.definition(), atSubmit.definition())
                    || !java.util.Objects.equals(now.valueDomain().get("values"),
                            atSubmit.valueDomain().get("values")));

        List<ScenarioRow> rows = new ArrayList<>();
        Map<String, Object> guard = new LinkedHashMap<>();
        guard.put("fieldDefVersionAtSubmit", atSubmit == null ? null : atSubmit.version());
        guard.put("fieldStatusAtSubmit", atSubmit == null ? null : atSubmit.status());
        guard.put("currentFieldDefVersion", now == null ? null : now.version());
        guard.put("currentFieldStatus", now == null ? "NOT_IN_CATALOG" : now.status());
        guard.put("versionDrift", drifted);

        if ((deprecated || drifted) && useCurrentMeaning) {
            // 铁律1：废止字段不得按现行含义回算历史
            guard.put("refusal", "REFUSED");
            guard.put("reason", "字段已废止/同名不同义；禁止按现行含义回算历史表单，历史仅按提交时 v"
                    + inst.catalogVersion() + " 语义解释");
            Map<String, Object> refused = Map.of("caseId", caseId, "sourceField", sourceField,
                    "guard", guard, "scenarios", List.of(),
                    "policy", "废止字段按现行含义回算历史一律拒绝");
            graph.audit("SUBSTITUTION_REFUSED", caseId, Map.of("field", sourceField));
            return refused;
        }

        @SuppressWarnings("unchecked")
        List<String> domain = atSubmit == null ? List.of()
                : (List<String>) atSubmit.valueDomain().getOrDefault("values", List.of());
        List<Population> pop = baselinePopulation(asOf, catalogVersion, purpose);
        int baseScore = scoreOf(venue);
        Integer baseRank = rankOf(pop, caseId, baseScore);

        for (String candidate : domain) {
            FormInstance trial = withValue(inst, sourceField, candidate);
            MappedValue mv = mapping.map(trial, sourceField, candidate);
            if (candidate.equals(original)) {
                // 当前取值：基线行，说明“现状”来自哪条契约/分支
                rows.add(new ScenarioRow("当前取值 " + sourceField + "=" + candidate, "BASELINE",
                        "现状基线：" + (mv.equivalent()
                                ? "按契约 " + mv.contractId() + " 映射为 " + mv.targetFieldId() + "=" + mv.targetValue()
                                : "现状即被拒绝：" + mv.evidence()),
                        baseScore, String.valueOf(venueRisk.gradeOf(baseScore).get("grade")), baseRank,
                        0, 0, false,
                        Map.of("contractId", mv.contractId() == null ? "" : mv.contractId(),
                                "contractVersion", mv.contractVersion(), "evidence", mv.evidence())));
                continue;
            }
            if (!mv.equivalent() || mv.targetValue() == null) {
                rows.add(new ScenarioRow("替换 " + sourceField + "=" + candidate, "REJECTED",
                        "无市级等价语义，编译期拒绝：" + mv.evidence(),
                        null, null, null, null, null, false,
                        Map.of("contractId", mv.contractId() == null ? "" : mv.contractId())));
                continue;
            }
            Map<String, String> trialStd = new LinkedHashMap<>(venue.stdValues());
            trialStd.put(mv.targetFieldId(), mv.targetValue());
            int score = venueRisk.totalScore(venueRisk.evaluate(trialStd, venue.missing()));
            Integer rank = rankOf(pop, caseId, score);
            rows.add(new ScenarioRow("替换 " + sourceField + "=" + candidate, "COMPUTED",
                    "按提交时 v" + inst.catalogVersion() + " 契约 " + mv.contractId()
                            + " 映射为 " + mv.targetFieldId() + "=" + mv.targetValue(),
                    score, String.valueOf(venueRisk.gradeOf(score).get("grade")), rank,
                    score - baseScore, delta(baseRank, rank),
                    baseRank != null && !baseRank.equals(rank),
                    Map.of("contractId", mv.contractId(), "contractVersion", mv.contractVersion(),
                            "evidence", mv.evidence(),
                            "semanticsNote", deprecated || drifted
                                    ? "现行字段已废止/改名，本情景严格按提交时语义计算，未按现行含义回算"
                                    : "同版本语义替换")));
        }
        Map<String, Object> subOut = assembleMap("SUBSTITUTION", caseId, sourceField, guard, baseScore, baseRank, rows,
                "候选值限定为提交时目录版本的值域；REJECTED 取值不入统、不进贡献");
        graph.audit("SUBSTITUTION_SCENARIO", caseId, Map.of("field", sourceField,
                "rejected", rows.stream().filter(r -> "REJECTED".equals(r.status())).count()));
        return subOut;
    }

    // ============================ 单位换算情景 ============================

    /**
     * 单位换算边际影响：仅承认 APPROVED 契约中的 transform；
     * 任何无批准换算的尝试一律 BLOCKED，不产生得分/贡献。
     */
    public Map<String, Object> unitScenarios(String caseId, String sourceField,
                                             LocalDate asOf, Integer catalogVersion, String purpose) {
        VenueRiskService.Venue venue = graph.findVenue(caseId, asOf, catalogVersion, purpose);
        FormInstance inst = venue.instance();
        String raw = inst.values().get(sourceField);
        if (raw == null) {
            throw new com.fire.fieldgov.web.GovernanceException("源字段 " + sourceField + " 不在该表单实例中");
        }

        List<ScenarioRow> rows = new ArrayList<>();
        Map<String, Object> guard = new LinkedHashMap<>();
        guard.put("sourceField", sourceField);
        guard.put("rawValue", raw);

        List<Population> pop = baselinePopulation(asOf, catalogVersion, purpose);
        int baseScore = scoreOf(venue);
        Integer baseRank = rankOf(pop, caseId, baseScore);

        // 市级标准字段：表单直接采用，MappingEngine 恒等映射 x*1（已内置批准，无需契约行）
        if (mapping.isStandardField(sourceField)) {
            String converted = mapping.applyTransform("x*1", raw);
            Map<String, String> trialStd = new LinkedHashMap<>(venue.stdValues());
            trialStd.put(sourceField, converted);
            int score = venueRisk.totalScore(venueRisk.evaluate(trialStd, venue.missing()));
            Integer rank = rankOf(pop, caseId, score);
            guard.put("approvedContract", "IDENTITY");
            guard.put("approvedTransform", "x*1");
            rows.add(new ScenarioRow("恒等换算 x*1（市级标准字段直接采用）", "COMPUTED",
                    "标准字段无需辖区契约，恒等映射为内置已批准路径；" + raw + " → " + converted,
                    score, String.valueOf(venueRisk.gradeOf(score).get("grade")), rank,
                    score - baseScore, delta(baseRank, rank),
                    baseRank != null && !baseRank.equals(rank),
                    Map.of("transform", "x*1", "convertedValue", converted, "targetField", sourceField,
                            "contractId", "IDENTITY", "contractStatus", "IDENTITY")));
            Map<String, Object> idOut = assembleMap("UNIT_CONVERSION", caseId, sourceField, guard, baseScore, baseRank, rows,
                    "恒等映射为内置已批准路径；无批准换算才 BLOCKED");
            graph.audit("UNIT_SCENARIO", caseId, Map.of("field", sourceField, "blocked", 0));
            return idOut;
        }

        List<MappingContract> contracts = catalog.allContracts().stream()
                .filter(c -> c.sourceFieldId().equals(sourceField))
                .filter(c -> "APPROVED".equals(c.status()) || "SUPERSEDED".equals(c.status()))
                .filter(c -> c.version() <= inst.catalogVersion())
                .toList();
        MappingContract approved = contracts.stream()
                .max(java.util.Comparator.comparingInt(MappingContract::version)).orElse(null);
        guard.put("approvedContract", approved == null ? null : approved.contractId());
        guard.put("approvedTransform", approved == null ? null
                : approved.values().stream().map(com.fire.fieldgov.model.MappingValue::transform)
                        .filter(t -> t != null).findFirst().orElse(null));

        if (approved == null) {
            rows.add(new ScenarioRow("单位换算", "BLOCKED",
                    "无已批准换算契约，源值 " + raw + " 不得进入贡献计算", null, null, null, null, null, false, Map.of()));
        } else {
            String transform = approved.values().stream()
                    .map(com.fire.fieldgov.model.MappingValue::transform).filter(t -> t != null).findFirst().orElse(null);
            String converted = mapping.applyTransform(transform, raw);
            String stdField = approved.targetFieldId();
            Map<String, String> trialStd = new LinkedHashMap<>(venue.stdValues());
            trialStd.put(stdField, converted);
            int score = venueRisk.totalScore(venueRisk.evaluate(trialStd, venue.missing()));
            Integer rank = rankOf(pop, caseId, score);
            rows.add(new ScenarioRow("批准换算 " + transform, "COMPUTED",
                    approved.evidenceNote() + "；" + raw + " → " + converted + " " + stdField,
                    score, String.valueOf(venueRisk.gradeOf(score).get("grade")), rank,
                    score - baseScore, delta(baseRank, rank),
                    baseRank != null && !baseRank.equals(rank),
                    Map.of("transform", transform, "convertedValue", converted, "targetField", stdField,
                            "contractId", approved.contractId(), "contractStatus", approved.status())));

            // 显式演示铁律3：当批准换算是非恒等公式时，把原始单位按 x*1 直取属于“无批准换算”，BLOCKED
            if (transform != null && !transform.replaceAll("\\s+", "").equals("x*1")
                    && !transform.replaceAll("\\s+", "").equals("x")) {
                rows.add(new ScenarioRow("未批准换算 x*1（原始单位直取）", "BLOCKED",
                        "不存在覆盖该取值的已批准 x*1 换算；无批准换算不得进入贡献计算，仅留阻塞痕迹",
                        null, null, null, null, null, false,
                        Map.of("wouldBeRawValue", raw, "blockedFromContribution", true)));
            }
        }
        Map<String, Object> unitOut = assembleMap("UNIT_CONVERSION", caseId, sourceField, guard, baseScore, baseRank, rows,
                "仅 APPROVED/SUPERSEDED 契约中的换算可计算；BLOCKED 行不产生得分与贡献");
        graph.audit("UNIT_SCENARIO", caseId, Map.of("field", sourceField,
                "blocked", rows.stream().filter(r -> "BLOCKED".equals(r.status())).count()));
        return unitOut;
    }

    // ============================ 稳定性报告 ============================

    public Map<String, Object> stabilityReport(String caseId, LocalDate asOf,
                                               Integer catalogVersion, String purpose) {
        VenueRiskService.Venue venue = graph.findVenue(caseId, asOf, catalogVersion, purpose);
        List<Population> pop = baselinePopulation(asOf, catalogVersion, purpose);
        int baseScore = scoreOf(venue);
        Integer baseRank = rankOf(pop, caseId, baseScore);
        String baseGrade = String.valueOf(venueRisk.gradeOf(baseScore).get("grade"));

        // 缺失情景汇总
        List<Map<String, Object>> missingSummary = new ArrayList<>();
        for (String f : venue.missing().keySet()) {
            Map<String, Object> ms = missingScenarios(caseId, f, asOf, catalogVersion, purpose);
            missingSummary.add(summarize(f, ms, baseRank));
        }
        // 替换情景汇总（按提交时语义；不触发 REFUSED 分支）
        List<Map<String, Object>> substitutionSummary = new ArrayList<>();
        for (String sourceField : venue.instance().values().keySet()) {
            Map<String, Object> ss = substitutionScenarios(caseId, sourceField, false, asOf, catalogVersion, purpose);
            substitutionSummary.add(summarize(sourceField, ss, baseRank));
        }
        // 单位换算汇总（仅数值源字段）
        List<Map<String, Object>> unitSummary = new ArrayList<>();
        for (String sourceField : venue.instance().values().keySet()) {
            FieldDef d = catalog.defsAsOf(venue.instance().catalogVersion(),
                    venue.instance().inspectDate()).get(sourceField);
            if (d != null && "numeric".equals(d.valueDomain().get("type"))) {
                Map<String, Object> us = unitScenarios(caseId, sourceField, asOf, catalogVersion, purpose);
                unitSummary.add(summarize(sourceField, us, baseRank));
            }
        }

        boolean anyFlip = false;
        boolean anyGradeChange = false;
        for (Map<String, Object> s : missingSummary) {
            anyFlip |= Boolean.TRUE.equals(s.get("rankFlipPossible"));
            anyGradeChange |= Boolean.TRUE.equals(s.get("gradeChangePossible"));
        }
        for (Map<String, Object> s : substitutionSummary) {
            anyFlip |= Boolean.TRUE.equals(s.get("rankFlipPossible"));
            anyGradeChange |= Boolean.TRUE.equals(s.get("gradeChangePossible"));
        }
        for (Map<String, Object> s : unitSummary) {
            anyFlip |= Boolean.TRUE.equals(s.get("rankFlipPossible"));
            anyGradeChange |= Boolean.TRUE.equals(s.get("gradeChangePossible"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", caseId);
        out.put("district", venue.district());
        out.put("baseline", Map.of("score", baseScore, "grade", baseGrade,
                "rank", baseRank == null ? "" : baseRank,
                "rankNote", baseRank == null ? "访问策略受限或并列，参考排名字段" : "竞赛排名（严格领先者+1）"));
        out.put("missingScenarios", missingSummary);
        out.put("substitutionScenarios", substitutionSummary);
        out.put("unitScenarios", unitSummary);
        out.put("stability", Map.of(
                "gradeStable", !anyGradeChange,
                "rankStable", !anyFlip,
                "intervalOnlyBecauseOfMissing", !venue.missing().isEmpty(),
                "blockedConversions", countStatus(unitSummary, "BLOCKED"),
                "rejectedSubstitutions", countStatus(substitutionSummary, "REJECTED")));
        out.put("policies", List.of(
                "废止/改名字段不得按现行含义回算历史（REFUSED）",
                "缺失使排名翻转时只给区间，不给定点名次",
                "无批准换算 BLOCKED，不进入贡献计算"));
        graph.audit("STABILITY_REPORT", caseId, Map.of(
                "blockedConversions", countStatus(unitSummary, "BLOCKED"),
                "rejectedSubstitutions", countStatus(substitutionSummary, "REJECTED"),
                "gradeStable", !anyGradeChange, "rankStable", !anyFlip));
        return out;
    }

    // ============================ 公共工具 ============================

    public record Population(String caseId, int score) {}

    /** 基线总体：访问通过的场所及其基线分数。 */
    public List<Population> baselinePopulation(LocalDate asOf, Integer catalogVersion, String purpose) {
        List<Population> out = new ArrayList<>();
        for (VenueRiskService.Venue v : venueRisk.assemble(asOf, catalogVersion, purpose)) {
            if (!v.access().allowed()) continue;
            out.add(new Population(v.caseId(), scoreOf(v)));
        }
        return out;
    }

    public int scoreOf(VenueRiskService.Venue v) {
        return venueRisk.totalScore(venueRisk.evaluate(v.stdValues(), v.missing()));
    }

    /** 竞赛排名：1 + 严格高于该分数的场所数；并列同名次。 */
    public Integer rankOf(List<Population> pop, String caseId, int score) {
        if (pop.stream().noneMatch(p -> p.caseId().equals(caseId))) return null;
        int rank = 1;
        for (Population p : pop) if (p.score() > score) rank++;
        return rank;
    }

    private Integer delta(Integer base, Integer now) {
        return base == null || now == null ? null : now - base;
    }

    private FormInstance withValue(FormInstance inst, String field, String value) {
        Map<String, String> values = new LinkedHashMap<>(inst.values());
        values.put(field, value);
        return new FormInstance(inst.instanceId(), inst.caseId(), inst.templateId(), inst.district(),
                inst.catalogVersion(), inst.inspectDate(), values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summarize(String field, Map<String, Object> scenarioMap, Integer baseRank) {
        List<ScenarioRow> rows = (List<ScenarioRow>) scenarioMap.get("scenarios");
        List<ScenarioRow> computed = rows.stream().filter(r -> "COMPUTED".equals(r.status())).toList();
        Integer min = computed.stream().map(ScenarioRow::score).min(Integer::compare).orElse(null);
        Integer max = computed.stream().map(ScenarioRow::score).max(Integer::compare).orElse(null);
        boolean flip = computed.stream().anyMatch(ScenarioRow::rankFlip);
        boolean gradeChange = computed.stream().anyMatch(r -> r.grade() != null
                && !java.util.Objects.equals(r.grade(), scenarioMap.get("baselineGrade")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("guard", scenarioMap.get("guard"));
        m.put("computed", computed.size());
        m.put("blocked", rows.stream().filter(r -> "BLOCKED".equals(r.status())).count());
        m.put("rejected", rows.stream().filter(r -> "REJECTED".equals(r.status())).count());
        m.put("unreachable", rows.stream().filter(r -> "UNREACHABLE".equals(r.status())).count());
        m.put("scoreInterval", min == null ? null : Map.of("min", min, "max", max));
        m.put("gradeInterval", min == null ? null : List.of(
                venueRisk.gradeOf(max).get("grade"), venueRisk.gradeOf(min).get("grade")));
        List<Integer> ranks = computed.stream().map(ScenarioRow::rank).filter(java.util.Objects::nonNull).toList();
        m.put("rankInterval", ranks.isEmpty() ? null
                : Map.of("best", ranks.stream().min(Integer::compare).orElse(0),
                          "worst", ranks.stream().max(Integer::compare).orElse(0)));
        m.put("rankFlipPossible", flip);
        m.put("gradeChangePossible", gradeChange);
        m.put("baselineRank", baseRank);
        m.put("rows", rows);
        return m;
    }

    private long countStatus(List<Map<String, Object>> summaries, String status) {
        String key = switch (status) {
            case "BLOCKED" -> "blocked";
            case "REJECTED" -> "rejected";
            case "UNREACHABLE" -> "unreachable";
            default -> throw new com.fire.fieldgov.web.GovernanceException("未知情景状态 " + status);
        };
        return summaries.stream().mapToLong(s -> ((Number) s.getOrDefault(key, 0)).longValue()).sum();
    }

    private Map<String, Object> assembleInterval(String kind, String caseId, String field, String missingReason,
                                                 int baseScore, Integer baseRank,
                                                 List<ScenarioRow> rows, String policy) {
        Map<String, Object> out = assembleMap(kind, caseId, field,
                Map.of("missingReason", missingReason), baseScore, baseRank, rows, policy);
        out.put("baselineGrade", venueRisk.gradeOf(baseScore).get("grade"));
        return out;
    }

    private Map<String, Object> assembleMap(String kind, String caseId, String field, Map<String, Object> guard,
                                            int baseScore, Integer baseRank,
                                            List<ScenarioRow> rows, String policy) {
        List<ScenarioRow> computed = rows.stream().filter(r -> "COMPUTED".equals(r.status())).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("caseId", caseId);
        out.put("field", field);
        out.put("guard", guard);
        out.put("baselineScore", baseScore);
        out.put("baselineGrade", venueRisk.gradeOf(baseScore).get("grade"));
        out.put("baselineRank", baseRank);
        out.put("scenarios", rows);
        if (!computed.isEmpty()) {
            int min = computed.stream().mapToInt(ScenarioRow::score).min().orElse(baseScore);
            int max = computed.stream().mapToInt(ScenarioRow::score).max().orElse(baseScore);
            List<Integer> ranks = computed.stream().map(ScenarioRow::rank).filter(java.util.Objects::nonNull).toList();
            out.put("scoreInterval", Map.of("min", min, "max", max));
            out.put("gradeInterval", List.of(
                    venueRisk.gradeOf(max).get("grade"), venueRisk.gradeOf(min).get("grade")));
            out.put("rankInterval", ranks.isEmpty() ? null : Map.of(
                    "best", ranks.stream().min(Integer::compare).orElse(0),
                    "worst", ranks.stream().max(Integer::compare).orElse(0)));
            out.put("rankFlipPossible", computed.stream().anyMatch(ScenarioRow::rankFlip));
        }
        out.put("policy", policy);
        return out;
    }
}
