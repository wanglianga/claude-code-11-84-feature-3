package com.fire.fieldgov.web;

import com.fire.fieldgov.explain.ExplainGraphService;
import com.fire.fieldgov.explain.MonotonicityService;
import com.fire.fieldgov.explain.RuleRepository;
import com.fire.fieldgov.explain.ScenarioService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 高风险场所排序：规则解释图、字段贡献分解、缺失/替换/单位换算情景、
 * 单调性检验与稳定性报告（特征3）。所有端点走统一认证拦截器与服务端访问策略。
 */
@RestController
@RequestMapping("/api/explain")
public class ExplainController {

    private final ExplainGraphService graph;
    private final ScenarioService scenarios;
    private final MonotonicityService monotonicity;
    private final RuleRepository rules;

    public ExplainController(ExplainGraphService graph, ScenarioService scenarios,
                             MonotonicityService monotonicity, RuleRepository rules) {
        this.graph = graph;
        this.scenarios = scenarios;
        this.monotonicity = monotonicity;
        this.rules = rules;
    }

    @GetMapping("/rules")
    public Map<String, Object> rules() {
        return Map.of("rules", rules.allRules(), "monotonicDeclarations", rules.monotonicDeclarations(),
                "gradeBands", com.fire.fieldgov.explain.VenueRiskService.GRADE_BANDS);
    }

    /** 全量排序（含缺失区间）；method=PATH|SHAPLEY 决定贡献口径。 */
    @GetMapping("/ranking")
    public Map<String, Object> ranking(@RequestParam(required = false) String asOf,
                                       @RequestParam(required = false) Integer catalogVersion,
                                       @RequestParam(defaultValue = "STAT") String purpose,
                                       @RequestParam(defaultValue = "PATH") String method) {
        return graph.ranking(parseDate(asOf), catalogVersion, purpose, method);
    }

    /** 单场所规则解释图。 */
    @GetMapping("/venues/{caseId}/graph")
    public Map<String, Object> graph(@PathVariable String caseId,
                                     @RequestParam(required = false) String asOf,
                                     @RequestParam(required = false) Integer catalogVersion,
                                     @RequestParam(defaultValue = "STAT") String purpose) {
        return graph.explanationGraph(caseId, parseDate(asOf), catalogVersion, purpose);
    }

    /** 单场所贡献分解（PATH 路径归因 / SHAPLEY 联盟贡献）。 */
    @GetMapping("/venues/{caseId}/contributions")
    public Map<String, Object> contributions(@PathVariable String caseId,
                                             @RequestParam(required = false) String asOf,
                                             @RequestParam(required = false) Integer catalogVersion,
                                             @RequestParam(defaultValue = "STAT") String purpose,
                                             @RequestParam(defaultValue = "PATH") String method) {
        var venue = graph.findVenue(caseId, parseDate(asOf), catalogVersion, purpose);
        var hits = graph.findVenueRisk().evaluate(venue.stdValues(), venue.missing());
        return "SHAPLEY".equalsIgnoreCase(method)
                ? graph.shapleyContributions(hits)
                : graph.pathContributions(hits);
    }

    /** 缺失情景：枚举可达取值，给分数/等级/名次区间。 */
    @GetMapping("/venues/{caseId}/scenarios/missing")
    public Map<String, Object> missing(@PathVariable String caseId,
                                       @RequestParam("field") String stdField,
                                       @RequestParam(required = false) String asOf,
                                       @RequestParam(required = false) Integer catalogVersion,
                                       @RequestParam(defaultValue = "STAT") String purpose) {
        return scenarios.missingScenarios(caseId, stdField, parseDate(asOf), catalogVersion, purpose);
    }

    /** 替换情景：同版本值域替换；useCurrentMeaning=true 对废止字段将被 REFUSED。 */
    @GetMapping("/venues/{caseId}/scenarios/substitution")
    public Map<String, Object> substitution(@PathVariable String caseId,
                                            @RequestParam("field") String sourceField,
                                            @RequestParam(defaultValue = "false") boolean useCurrentMeaning,
                                            @RequestParam(required = false) String asOf,
                                            @RequestParam(required = false) Integer catalogVersion,
                                            @RequestParam(defaultValue = "STAT") String purpose) {
        return scenarios.substitutionScenarios(caseId, sourceField, useCurrentMeaning,
                parseDate(asOf), catalogVersion, purpose);
    }

    /** 单位换算情景：无批准换算 BLOCKED。 */
    @GetMapping("/venues/{caseId}/scenarios/unit")
    public Map<String, Object> unit(@PathVariable String caseId,
                                    @RequestParam("field") String sourceField,
                                    @RequestParam(required = false) String asOf,
                                    @RequestParam(required = false) Integer catalogVersion,
                                    @RequestParam(defaultValue = "STAT") String purpose) {
        return scenarios.unitScenarios(caseId, sourceField, parseDate(asOf), catalogVersion, purpose);
    }

    /** 稳定性报告：替换/缺失/单位换算对等级与名次的边际影响汇总。 */
    @GetMapping("/venues/{caseId}/stability")
    public Map<String, Object> stability(@PathVariable String caseId,
                                         @RequestParam(required = false) String asOf,
                                         @RequestParam(required = false) Integer catalogVersion,
                                         @RequestParam(defaultValue = "STAT") String purpose) {
        return scenarios.stabilityReport(caseId, parseDate(asOf), catalogVersion, purpose);
    }

    /** 单调性 A：现行规则结构扫描。 */
    @GetMapping("/monotonicity/rules")
    public Map<String, Object> monotonicRules() {
        return monotonicity.sweepRules();
    }

    /** 单调性 B：规则更新候选校验（不落库）。 */
    @PostMapping("/monotonicity/rule-update")
    public Map<String, Object> ruleUpdate(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> branches = (List<Map<String, Object>>) body.get("branches");
        return monotonicity.checkRuleUpdate(
                String.valueOf(body.get("ruleId")),
                ((Number) body.getOrDefault("candidateVersion", 1)).intValue(),
                String.valueOf(body.get("targetField")),
                String.valueOf(body.getOrDefault("ruleType", "ENUM_BRANCH")),
                branches,
                ((Number) body.getOrDefault("defaultPoints", 0)).intValue(),
                String.valueOf(body.getOrDefault("monotonicDirection", "NONE")));
    }

    /** 单调性 C：字段版本漂移与实证扰动。 */
    @GetMapping("/monotonicity/drift")
    public Map<String, Object> drift(@RequestParam(required = false) String asOf,
                                     @RequestParam(required = false) Integer catalogVersion,
                                     @RequestParam(defaultValue = "STAT") String purpose) {
        return monotonicity.driftCheck(parseDate(asOf), catalogVersion, purpose);
    }

    private LocalDate parseDate(String asOf) {
        return asOf == null || asOf.isBlank() ? LocalDate.now() : LocalDate.parse(asOf);
    }
}
