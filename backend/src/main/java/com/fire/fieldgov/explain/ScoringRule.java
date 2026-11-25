package com.fire.fieldgov.explain;

import java.util.List;
import java.util.Map;

/**
 * 排序评分规则（rule_id + version 唯一，版本化并存）。
 * branches 为有序分支表，每条分支：{op, value, points, label}；
 * 命中第一条满足的分支，均不命中取 defaultPoints。
 * monotonicDirection 是“已声明安全单调关系”：ASC 表示字段值沿声明序增大时得分不得下降。
 */
public record ScoringRule(
        String ruleId,
        int version,
        String title,
        String targetField,
        String ruleType,              // ENUM_BRANCH / NUMERIC_BRANCH
        List<Map<String, Object>> branches,
        int defaultPoints,
        String monotonicDirection,    // NONE / ASC / DESC
        String status) {             // ACTIVE / SUPERSEDED

    public boolean numeric() { return "NUMERIC_BRANCH".equals(ruleType); }
}
