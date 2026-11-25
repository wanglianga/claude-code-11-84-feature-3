package com.fire.fieldgov.explain;

import com.fire.fieldgov.db.DbJson;
import com.fire.fieldgov.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则解释图 + 字段贡献分解 + 排序。
 * 贡献分解同时给出两种口径：
 *  - PATH 规则路径归因：每条规则分支独立可审计，分数尺度下可加、份额相加=100%；不表达规则交互；
 *  - SHAPLEY：以“分级阈值效用” v(S)=gradeRank(Σpoints) 做 Shapley，边际含跨规则交互；
 *    并附分数尺度对照（线性效用下 Shapley 退化为路径归因，作为效率校验）。
 */
@Service
public class ExplainGraphService {

    private final VenueRiskService venueRisk;
    private final RuleRepository rules;
    private final JdbcTemplate jdbc;

    public ExplainGraphService(VenueRiskService venueRisk, RuleRepository rules, JdbcTemplate jdbc) {
        this.venueRisk = venueRisk;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    // ============================ 排序（含缺失区间） ============================

    public record RankedVenue(String caseId, String district, Integer pointRank,
                              Map<String, Object> rankInterval, int score,
                              Map<String, Object> scoreInterval, String grade,
                              List<String> gradeInterval, boolean indeterminate,
                              List<String> missingFields, List<VenueRiskService.RuleHit> ruleHits,
                              Map<String, Object> contributions) {}

    /**
     * 全量排序。含缺失字段的场所只给分数/等级/排名区间；区间宽度>0 时 pointRank=null。
     */
    public Map<String, Object> ranking(java.time.LocalDate asOf, Integer catalogVersion,
                                       String purpose, String method) {
        List<VenueRiskService.Venue> venues = venueRisk.assemble(asOf, catalogVersion, purpose);
        List<VenueRiskService.Venue> visible = new ArrayList<>();
        List<Map<String, Object>> denied = new ArrayList<>();
        for (VenueRiskService.Venue v : venues) {
            if (v.access().allowed()) visible.add(v);
            else denied.add(Map.of("caseId", v.caseId(), "district", v.district(),
                    "reason", v.access().reason()));
        }

        List<int[]> bounds = new ArrayList<>(); // {min,max}
        List<List<VenueRiskService.RuleHit>> allHits = new ArrayList<>();
        for (VenueRiskService.Venue v : visible) {
            List<VenueRiskService.RuleHit> hits = venueRisk.evaluate(v.stdValues(), v.missing());
            allHits.add(hits);
            int min = venueRisk.totalScore(hits);
            int max = min;
            for (String missingField : v.missing().keySet()) {
                // 无已批准映射路径的缺失字段（DRAFT/拒绝）不得放宽区间上限
                max += venueRisk.maxReachablePoints(missingField);
            }
            bounds.add(new int[]{min, max});
        }

        List<RankedVenue> ranked = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            VenueRiskService.Venue v = visible.get(i);
            int sMin = bounds.get(i)[0], sMax = bounds.get(i)[1];
            // 竞赛排名：严格领先者数量 +1；最优/最差名次分别按对方区间端点计算
            int bestRank = 1, worstRank = 1;
            for (int j = 0; j < visible.size(); j++) {
                if (j == i) continue;
                if (bounds.get(j)[0] > sMax) bestRank++;          // 对方即便最低也严格高于我最高
                if (bounds.get(j)[1] > sMin) worstRank++;         // 对方最高高于我最低即可能压过我
            }
            boolean indeterminate = bestRank != worstRank || sMin != sMax;
            Map<String, Object> scoreInterval = Map.of("min", sMin, "max", sMax);
            List<String> gradeInterval = List.of(
                    String.valueOf(venueRisk.gradeOf(sMax).get("grade")),
                    String.valueOf(venueRisk.gradeOf(sMin).get("grade")));
            Map<String, Object> contributions = "SHAPLEY".equalsIgnoreCase(method)
                    ? shapleyContributions(allHits.get(i))
                    : pathContributions(allHits.get(i));
            ranked.add(new RankedVenue(v.caseId(), v.district(),
                    indeterminate ? null : bestRank,
                    Map.of("best", bestRank, "worst", worstRank),
                    sMin, scoreInterval,
                    String.valueOf(venueRisk.gradeOf(sMin).get("grade")),
                    gradeInterval, indeterminate, new ArrayList<>(v.missing().keySet()),
                    allHits.get(i), contributions));
        }
        ranked.sort((a, b) -> {
            int cmp = Integer.compare(((Number) b.scoreInterval().get("max")).intValue(),
                    ((Number) a.scoreInterval().get("max")).intValue());
            if (cmp != 0) return cmp;
            return Integer.compare(b.score(), a.score());
        });

        Map<String, Object> rankAudit = new LinkedHashMap<>();
        rankAudit.put("asOf", String.valueOf(asOf));
        rankAudit.put("catalogVersion", catalogVersion);
        rankAudit.put("purpose", purpose);
        rankAudit.put("method", method);
        rankAudit.put("venues", visible.size());
        audit("RANKING", null, rankAudit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", String.valueOf(asOf));
        out.put("catalogVersion", catalogVersion);
        out.put("purpose", purpose);
        out.put("method", method);
        out.put("gradeBands", VenueRiskService.GRADE_BANDS);
        out.put("ruleVersions", rules.activeRules().stream()
                .map(r -> r.ruleId() + ":v" + r.version()).sorted().toList());
        out.put("ranking", ranked);
        out.put("accessDenied", denied);
        out.put("intervalPolicy", "缺失字段使名次可能翻转时，只发布[best,worst]区间，不给定点名次");
        return out;
    }

    // ============================ 贡献分解 ============================

    /** 规则路径归因：每条命中规则一条，份额按得分占比。 */
    public Map<String, Object> pathContributions(List<VenueRiskService.RuleHit> hits) {
        int total = venueRisk.totalScore(hits);
        List<Map<String, Object>> items = new ArrayList<>();
        for (VenueRiskService.RuleHit h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", h.ruleId());
            m.put("ruleVersion", h.ruleVersion());
            m.put("title", h.title());
            m.put("targetField", h.targetField());
            m.put("matchedBranch", h.matchedBranch());
            m.put("branchLabel", h.branchLabel());
            m.put("actualValue", h.actualValue());
            m.put("points", h.points());
            m.put("sharePct", total == 0 ? 0 : Math.round(h.points() * 1000.0 / total) / 10.0);
            m.put("fieldMissing", h.fieldMissing());
            m.put("monotonicDirection", h.monotonicDirection());
            m.put("methodNote", "路径归因：逐条规则可审计；规则间交互不在此口径表达");
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", "PATH");
        out.put("totalScore", total);
        out.put("items", items);
        out.put("efficiencyCheck", Map.of("sumPoints", total, "equalsTotal",
                items.stream().mapToInt(i -> (int) i.get("points")).sum() == total));
        return out;
    }

    /**
     * Shapley 式贡献。玩家=当前有取值且产生得分的规则字段；
     * 联盟效用 v(S)=分级阈值效用（非线性，体现交互），并附分数尺度对照（线性，应与路径归因一致）。
     */
    public Map<String, Object> shapleyContributions(List<VenueRiskService.RuleHit> hits) {
        List<VenueRiskService.RuleHit> players = hits.stream().filter(h -> !h.fieldMissing()).toList();
        int n = players.size();
        double[] phiGrade = new double[n];
        double[] phiScore = new double[n];
        int coalitions = (int) Math.pow(2, n);
        for (int mask = 0; mask < coalitions; mask++) {
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) continue;
                int s = Integer.bitCount(mask);
                double weight = fact(s) * fact(n - s - 1) / fact(n);
                int with = scoreOfMask(players, mask | (1 << i));
                int without = scoreOfMask(players, mask);
                phiGrade[i] += weight * (venueRisk.gradeUtility(with) - venueRisk.gradeUtility(without));
                phiScore[i] += weight * (with - without);
            }
        }
        int fullScore = players.stream().mapToInt(VenueRiskService.RuleHit::points).sum();
        List<Map<String, Object>> items = new ArrayList<>();
        double sumPhiRaw = 0;
        for (int i = 0; i < n; i++) {
            sumPhiRaw += phiGrade[i];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", players.get(i).ruleId());
            m.put("targetField", players.get(i).targetField());
            m.put("title", players.get(i).title());
            m.put("points", players.get(i).points());
            m.put("shapleyGradeUtility", round3(phiGrade[i]));
            m.put("shapleyScoreScale", round3(phiScore[i]));
            m.put("methodNote", "Shapley：遍历全部联盟的平均边际，含规则交互；缺失字段不做玩家（其影响走缺失情景区间）");
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", "SHAPLEY");
        out.put("players", n);
        out.put("coalitionEvaluations", coalitions);
        out.put("utilityDefinition", "v(S)=分级档位rank(RED=4…NONE=0)，阈值型非线性效用");
        out.put("totalScore", fullScore);
        out.put("gradeUtilityOfFullSet", venueRisk.gradeUtility(fullScore));
        out.put("items", items);
        double expected = venueRisk.gradeUtility(fullScore) - venueRisk.gradeUtility(0);
        // 效率判定用未舍入的原始和；展示值单独保留 3 位小数
        out.put("efficiencyCheck", Map.of(
                "sumShapleyGradeUtility", round3(sumPhiRaw),
                "expected", expected,
                "pass", Math.abs(sumPhiRaw - expected) < 1e-9,
                "scoreScaleMatchesPath", items.stream()
                        .allMatch(it -> Math.round((double) it.get("shapleyScoreScale")) == (int) it.get("points"))));
        return out;
    }

    private int scoreOfMask(List<VenueRiskService.RuleHit> players, int mask) {
        int sum = 0;
        for (int i = 0; i < players.size(); i++) if ((mask & (1 << i)) != 0) sum += players.get(i).points();
        return sum;
    }

    private double fact(int k) {
        double f = 1;
        for (int i =2; i <= k; i++) f *= i;
        return f;
    }

    private double round3(double d) { return Math.round(d * 1000.0) / 1000.0; }

    // ============================ 规则解释图 ============================

    /**
     * 单场所解释图：源字段(版本/废止) → 契约/换算(批准) → 标准值 → 规则分支 → 总分/等级/排名。
     * 节点/边均带状态，废止、拒绝、阻塞、缺失以独立节点类型显式呈现。
     */
    public Map<String, Object> explanationGraph(String caseId, java.time.LocalDate asOf,
                                                Integer catalogVersion, String purpose) {
        VenueRiskService.Venue venue = findVenue(caseId, asOf, catalogVersion, purpose);
        List<VenueRiskService.RuleHit> hits = venueRisk.evaluate(venue.stdValues(), venue.missing());
        int score = venueRisk.totalScore(hits);
        Map<String, Object> grade = venueRisk.gradeOf(score);

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();
        for (VenueRiskService.RuleHit hit : hits) {
            String stdField = hit.targetField();
            VenueRiskService.Provenance p = venue.provenance().get(stdField);
            String ruleNode = "rule:" + hit.ruleId();
            if (hit.fieldMissing() || p == null) {
                String missNode = "missing:" + stdField;
                nodes.add(node(missNode, "MISSING", stdField,
                        Map.of("reason", venue.missing().getOrDefault(stdField, "字段缺失"),
                                "ruleId", hit.ruleId(), "pointsWhenMissing", 0)));
                edges.add(edge(missNode, ruleNode, "缺失：规则不触发（0分），可能性交缺失情景器"));
            } else {
                String srcNode = "source:" + p.sourceField();
                Map<String, Object> srcMeta = new LinkedHashMap<>();
                srcMeta.put("sourceField", p.sourceField());
                srcMeta.put("sourceValue", p.sourceValue());
                srcMeta.put("fieldDefVersionAtSubmit", p.fieldDefVersionAtSubmit());
                srcMeta.put("fieldStatusAtSubmit", p.fieldStatusAtSubmit());
                srcMeta.put("currentFieldDefVersion", p.currentFieldDefVersion());
                srcMeta.put("fieldStatusNow", p.fieldStatusNow());
                srcMeta.put("versionDrift", p.versionDrift());
                nodes.add(node(srcNode, "SOURCE", p.sourceField(), srcMeta));

                if (p.contractId() != null) {
                    String cNode = "contract:" + p.contractId();
                    Map<String, Object> cMeta = new LinkedHashMap<>();
                    cMeta.put("contractId", p.contractId());
                    cMeta.put("contractVersion", p.contractVersion());
                    cMeta.put("status", p.contractStatus());
                    cMeta.put("transform", p.transform());
                    cMeta.put("equivalent", p.equivalent());
                    cMeta.put("evidence", p.evidence());
                    cMeta.put("viaEvent", p.viaEvent());
                    String cType = !p.equivalent() ? "REJECTED"
                            : "EVENT_REPLAY".equals(p.contractId()) || "IDENTITY".equals(p.contractId())
                            ? "CONTRACT_INTERNAL" : "CONTRACT";
                    nodes.add(node(cNode, cType, p.contractId(), cMeta));
                    edges.add(edge(srcNode, cNode, p.transform() == null ? "逐值证据映射" : "换算 " + p.transform()));
                    edges.add(edge(cNode, "std:" + stdField, "标准值 " + p.targetValue()));
                } else {
                    edges.add(edge(srcNode, "std:" + stdField, "无契约（语义未决）"));
                }
                nodes.add(node("std:" + stdField, "STD", stdField,
                        Map.of("value", p.targetValue(), "viaEvent", p.viaEvent())));
                edges.add(edge("std:" + stdField, ruleNode,
                        "命中分支 " + hit.matchedBranch() + " → " + hit.points() + " 分"));
            }
            Map<String, Object> ruleMeta = new LinkedHashMap<>();
            ruleMeta.put("ruleId", hit.ruleId());
            ruleMeta.put("ruleVersion", hit.ruleVersion());
            ruleMeta.put("title", hit.title());
            ruleMeta.put("branch", hit.matchedBranch());
            ruleMeta.put("branchLabel", hit.branchLabel());
            ruleMeta.put("points", hit.points());
            ruleMeta.put("monotonicDirection", hit.monotonicDirection());
            nodes.add(node(ruleNode, "RULE", hit.ruleId(), ruleMeta));
            edges.add(edge(ruleNode, "score:total", hit.points() + " 分计入总分"));
        }
        nodes.add(node("score:total", "SCORE", "TOTAL",
                Map.of("score", score, "grade", grade.get("grade"), "gradeLabel", grade.get("label"),
                        "gradeRank", grade.get("rank"))));

        // 同一源字段/契约可能被多条规则引用：按节点 id 去重，保留首次出现顺序
        Map<String, Map<String, Object>> dedup = new LinkedHashMap<>();
        for (Map<String, Object> n : nodes) dedup.putIfAbsent(String.valueOf(n.get("id")), n);
        audit("EXPLAIN_GRAPH", caseId, Map.of("asOf", String.valueOf(asOf), "purpose", purpose));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", caseId);
        out.put("district", venue.district());
        out.put("asOf", String.valueOf(asOf));
        out.put("nodes", new ArrayList<>(dedup.values()));
        out.put("edges", edges);
        out.put("ruleHits", hits);
        out.put("score", score);
        out.put("grade", grade);
        out.put("legend", Map.of(
                "SOURCE", "表单源字段（含提交时版本/当前状态/版本漂移标记）",
                "CONTRACT", "已批准映射契约与换算（证据节点）",
                "CONTRACT_INTERNAL", "恒等映射或事件流重放（内部来源）",
                "REJECTED", "equivalent=false，编译期拒绝，不进入贡献",
                "MISSING", "字段缺失/语义未决，规则0分，可能性交缺失情景",
                "RULE", "评分规则分支", "SCORE", "总分与分级"));
        return out;
    }

    public VenueRiskService.Venue findVenue(String caseId, java.time.LocalDate asOf,
                                            Integer catalogVersion, String purpose) {
        return venueRisk.assemble(asOf, catalogVersion, purpose).stream()
                .filter(v -> v.caseId().equals(caseId)).findFirst()
                .orElseThrow(() -> new com.fire.fieldgov.web.GovernanceException(
                        "场所 " + caseId + " 不存在或时点外无表单"));
    }

    public VenueRiskService findVenueRisk() { return venueRisk; }

    private Map<String, Object> node(String id, String type, String label, Map<String, Object> meta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("label", label);
        m.putAll(meta);
        return m;
    }

    private Map<String, String> edge(String from, String to, String label) {
        return Map.of("from", from, "to", to, "label", label);
    }

    public void audit(String method, String caseId, Map<String, Object> detail) {
        if (jdbc == null) return; // 纯逻辑单测环境无数据源，审计留痕降级为空操作
        jdbc.update("INSERT INTO explain_report(username,case_id,method,request_json) VALUES (?,?,?,?::jsonb)",
                CurrentUser.get().username(), caseId, method, DbJson.json(detail));
    }
}
