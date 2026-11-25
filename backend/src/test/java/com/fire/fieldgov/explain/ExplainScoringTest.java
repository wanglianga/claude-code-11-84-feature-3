package com.fire.fieldgov.explain;

import com.fire.fieldgov.catalog.CatalogService;
import com.fire.fieldgov.model.FieldDef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特征3 纯逻辑单测：评分/分级、缺失规则不触发、Shapley 效率、单调声明扫描。
 * 不依赖数据库：RuleRepository/CatalogService 以匿名子类桩替代。
 */
class ExplainScoringTest {

    private final ScoringRule hazard = new ScoringRule("RULE_HAZARD", 1, "隐患等级", "std.hazard_level",
            "ENUM_BRANCH", List.of(
            Map.of("op", "=", "value", "MAJOR", "points", 40, "label", "重大"),
            Map.of("op", "=", "value", "LIMITED", "points", 20, "label", "限期"),
            Map.of("op", "=", "value", "OBSERVE", "points", 8, "label", "观察"),
            Map.of("op", "=", "value", "RESCINDED", "points", 0, "label", "撤销")),
            0, "ASC", "ACTIVE");

    private final ScoringRule crack = new ScoringRule("RULE_CRACK", 1, "裂缝", "std.crack_width_mm",
            "NUMERIC_BRANCH", List.of(
            Map.of("op", ">=", "value", 20, "points", 30, "label", "≥20"),
            Map.of("op", ">=", "value", 10, "points", 15, "label", "≥10")),
            0, "ASC", "ACTIVE");

    private final ScoringRule rect = new ScoringRule("RULE_RECT", 1, "整改", "std.rectification_status",
            "ENUM_BRANCH", List.of(
            Map.of("op", "=", "value", "OPEN", "points", 20, "label", "未整改"),
            Map.of("op", "=", "value", "RECTIFIED", "points", 0, "label", "已整改")),
            0, "ASC", "ACTIVE");

    private final RuleRepository stubRules = new RuleRepository(null) {
        @Override public List<ScoringRule> activeRules() { return List.of(hazard, crack, rect); }
        @Override public List<Map<String, Object>> monotonicDeclarations() {
            return List.of(Map.of("declaration_id", "DECL_HAZARD", "field_id", "std.hazard_level",
                    "direction", "ASC", "scope", "SCORE",
                    "enum_order_json", List.of("RESCINDED", "OBSERVE", "LIMITED", "MAJOR"),
                    "note", "测试枚举序", "active", true));
        }
    };

    private final VenueRiskService vr = new VenueRiskService(null, null, null, null, null, stubRules, null);
    private final ExplainGraphService graph = new ExplainGraphService(vr, stubRules, null);

    @Test
    void gradeBands() {
        assertEquals("RED", vr.gradeOf(70).get("grade"));
        assertEquals("RED", vr.gradeOf(90).get("grade"));
        assertEquals("ORANGE", vr.gradeOf(50).get("grade"));
        assertEquals("YELLOW", vr.gradeOf(30).get("grade"));
        assertEquals("BLUE", vr.gradeOf(10).get("grade"));
        assertEquals("NONE", vr.gradeOf(0).get("grade"));
        assertEquals(4, vr.gradeUtility(75));
        assertEquals(0, vr.gradeUtility(0));
    }

    @Test
    void evaluateBranchesAndMissing() {
        List<VenueRiskService.RuleHit> hits = vr.evaluate(
                Map.of("std.hazard_level", "MAJOR", "std.crack_width_mm", "12",
                        "std.rectification_status", "OPEN"), Map.of());
        assertEquals(75, vr.totalScore(hits));
        assertEquals("RED", vr.gradeOf(vr.totalScore(hits)).get("grade"));

        // 裂缝 20mm 命中高档 30 分；9mm 落入默认 0 分
        assertEquals(30, vr.evaluate(Map.of("std.crack_width_mm", "20"), Map.of()).get(1).points());
        assertEquals(0, vr.evaluate(Map.of("std.crack_width_mm", "9"), Map.of()).get(1).points());

        // 缺失字段：规则不触发、0 分且显式标记 fieldMissing
        List<VenueRiskService.RuleHit> missingHits = vr.evaluate(
                Map.of("std.crack_width_mm", "12", "std.rectification_status", "OPEN"),
                Map.of("std.hazard_level", "无映射"));
        VenueRiskService.RuleHit h0 = missingHits.get(0);
        assertTrue(h0.fieldMissing());
        assertEquals(0, h0.points());
        assertEquals(35, vr.totalScore(missingHits)); // 15 + 20
    }

    @Test
    void shapleyEfficiencyAndInteraction() {
        List<VenueRiskService.RuleHit> hits = vr.evaluate(
                Map.of("std.hazard_level", "MAJOR", "std.crack_width_mm", "12",
                        "std.rectification_status", "OPEN"), Map.of());
        Map<String, Object> sh = graph.shapleyContributions(hits);
        assertEquals(8, sh.get("coalitionEvaluations")); // 2^3
        @SuppressWarnings("unchecked")
        Map<String, Object> eff = (Map<String, Object>) sh.get("efficiencyCheck");
        assertTrue((Boolean) eff.get("pass"));
        assertTrue((Boolean) eff.get("scoreScaleMatchesPath"));
        // 分数尺度线性效用下 Shapley 退化为各自路径得分
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sh.get("items");
        assertEquals(40, items.get(0).get("points"));
    }

    @Test
    void pathContributionsSumToTotal() {
        List<VenueRiskService.RuleHit> hits = vr.evaluate(
                Map.of("std.hazard_level", "LIMITED", "std.crack_width_mm", "20",
                        "std.rectification_status", "OPEN"), Map.of());
        Map<String, Object> path = graph.pathContributions(hits);
        assertEquals(70, path.get("totalScore")); // 20+30+20
        @SuppressWarnings("unchecked")
        Map<String, Object> eff = (Map<String, Object>) path.get("efficiencyCheck");
        assertTrue((Boolean) eff.get("equalsTotal"));
    }

    @Test
    void monotonicRuleUpdateGuarded() {
        FieldDef crackDef = new FieldDef("std.crack_width_mm", 1, "裂缝", "数值",
                Map.of("type", "numeric", "min", 0, "max", 500), "mm", "单次测量", "测量时刻",
                List.of(), "INTERNAL", LocalDate.of(2025, 1, 1), null, "ACTIVE", "CITY", null);
        CatalogService catalogStub = new CatalogService(null) {
            @Override public Map<String, FieldDef> currentDefs() { return Map.of("std.crack_width_mm", crackDef); }
        };
        MonotonicityService mono = new MonotonicityService(stubRules, vr, graph, catalogStub);

        // 反向赋分（值越大分越低）违反 ASC 声明 → 拒绝
        Map<String, Object> bad = mono.checkRuleUpdate("RULE_CRACK", 2, "std.crack_width_mm", "NUMERIC_BRANCH",
                List.of(Map.of("op", ">=", "value", 20, "points", 10, "label", "反向"),
                        Map.of("op", ">=", "value", 10, "points", 30, "label", "反向")),
                0, "ASC");
        assertEquals("REJECTED_MONOTONIC_BREACH", bad.get("decision"));

        // 合规候选 → 可接受
        Map<String, Object> good = mono.checkRuleUpdate("RULE_CRACK", 2, "std.crack_width_mm", "NUMERIC_BRANCH",
                List.of(Map.of("op", ">=", "value", 20, "points", 30, "label", "高"),
                        Map.of("op", ">=", "value", 10, "points", 15, "label", "中")),
                0, "ASC");
        assertEquals("ACCEPTABLE", good.get("decision"));
    }

    @Test
    void enumMonotonicBreachDetected() {
        // 枚举反向：MAJOR 0 分、RESCINDED 40 分，沿声明序得分下降
        ScoringRule inverted = new ScoringRule("RULE_HAZARD_BAD", 1, "反向隐患", "std.hazard_level",
                "ENUM_BRANCH", List.of(
                Map.of("op", "=", "value", "MAJOR", "points", 0, "label", "重大0分"),
                Map.of("op", "=", "value", "RESCINDED", "points", 40, "label", "撤销40分")),
                0, "ASC", "ACTIVE");
        RuleRepository badRepo = new RuleRepository(null) {
            @Override public List<ScoringRule> activeRules() { return List.of(inverted); }
            @Override public List<Map<String, Object>> monotonicDeclarations() {
                return stubRules.monotonicDeclarations();
            }
        };
        MonotonicityService mono = new MonotonicityService(badRepo, vr, graph,
                new CatalogService(null) {
                    @Override public Map<String, FieldDef> currentDefs() { return Map.of(); }
                });
        Map<String, Object> sweep = mono.sweepRules();
        assertFalse((Boolean) sweep.get("pass"));
        assertEquals(1, sweep.get("violationCount"));
    }
}
