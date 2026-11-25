package com.fire.fieldgov.explain;

import com.fire.fieldgov.db.DbJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 评分规则与单调声明的读取（规则版本化，解释固定到具体 rule 版本）。 */
@Service
public class RuleRepository {

    private final JdbcTemplate jdbc;

    public RuleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ScoringRule> allRules() {
        return jdbc.query(
                "SELECT rule_id, version, title, target_field, rule_type, branches_json, " +
                        "default_points, monotonic_direction, status FROM scoring_rule ORDER BY rule_id, version",
                (rs, i) -> new ScoringRule(
                        rs.getString("rule_id"),
                        rs.getInt("version"),
                        rs.getString("title"),
                        rs.getString("target_field"),
                        rs.getString("rule_type"),
                        (List<Map<String, Object>>) (List<?>) DbJson.toList(rs.getObject("branches_json")),
                        rs.getInt("default_points"),
                        rs.getString("monotonic_direction"),
                        rs.getString("status")));
    }

    /** 当前生效规则集（每个 rule_id 取 ACTIVE 的最大版本）。 */
    public List<ScoringRule> activeRules() {
        return allRules().stream()
                .filter(r -> "ACTIVE".equals(r.status()))
                .collect(java.util.stream.Collectors.groupingBy(ScoringRule::ruleId,
                        java.util.stream.Collectors.maxBy(java.util.Comparator.comparingInt(ScoringRule::version))))
                .values().stream().flatMap(java.util.Optional::stream).toList();
    }

    public ScoringRule ruleVersion(String ruleId, int version) {
        return allRules().stream()
                .filter(r -> r.ruleId().equals(ruleId) && r.version() == version).findFirst().orElse(null);
    }

    public List<Map<String, Object>> monotonicDeclarations() {
        return jdbc.queryForList(
                        "SELECT declaration_id, field_id, direction, scope, enum_order_json, note, active " +
                                "FROM monotonic_declaration ORDER BY declaration_id")
                .stream().map(row -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>(row);
                    Object eo = row.get("enum_order_json");
                    m.put("enum_order_json", eo == null ? null : DbJson.toList(eo));
                    return m;
                }).toList();
    }
}
