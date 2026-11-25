package com.fire.fieldgov.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结果审查器：最小发布组 + 互补抑制。
 * 规则（阈值 k，默认 4）：
 *  1) 原始计数 < k 的分组抑制（SMALL_GROUP）；
 *  2) 互补抑制：若被抑制组计数之和 hidden 满足 0 < hidden < k，则可由“总数-已发布组”反推，
 *     继续抑制当前最小的已发布组，直到 hidden=0 或 hidden≥k 或全部组被抑制；
 *  3) 总数仅在 total≥k 且（无抑制 或 hidden≥k）时发布，避免总数与分组相减暴露小格。
 * 被抑制组的原始计数绝不出现在响应中。
 */
@Service
public class SuppressionService {

    @Value("${app.suppression.threshold:4}")
    private int threshold;

    public int getThreshold() { return threshold; }

    public record GroupOutcome(String group, Integer count, boolean suppressed, String reason) {}

    public record DisclosureResult(List<GroupOutcome> groups, Integer total, boolean totalSuppressed,
                                   String totalReason, int rawTotal, int publishedGroupCount,
                                   int suppressedGroupCount, int threshold) {}

    public DisclosureResult apply(Map<String, Integer> rawCounts, boolean includeTotal) {
        int k = threshold;
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(rawCounts.entrySet());
        entries.sort(Comparator.comparing(Map.Entry<String, Integer>::getKey));

        Map<String, String> reason = new LinkedHashMap<>();
        List<Map.Entry<String, Integer>> suppressed = new ArrayList<>();
        List<Map.Entry<String, Integer>> published = new ArrayList<>();

        for (Map.Entry<String, Integer> e : entries) {
            if (e.getValue() < k) {
                suppressed.add(e);
                reason.put(e.getKey(), "SMALL_GROUP");
            } else {
                published.add(e);
            }
        }

        int hidden = suppressed.stream().mapToInt(Map.Entry::getValue).sum();
        // 互补抑制：持续把最小的已发布组拉入抑制，直到隐藏总量本身达到发布门槛
        published.sort(Comparator.comparingInt(Map.Entry<String, Integer>::getValue));
        while (hidden > 0 && hidden < k && !published.isEmpty()) {
            Map.Entry<String, Integer> move = published.remove(0);
            suppressed.add(move);
            hidden += move.getValue();
            reason.put(move.getKey(), "COMPLEMENTARY");
        }

        List<GroupOutcome> outcomes = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) {
            boolean isSuppressed = reason.containsKey(e.getKey());
            outcomes.add(new GroupOutcome(e.getKey(),
                    isSuppressed ? null : e.getValue(),
                    isSuppressed,
                    reason.get(e.getKey())));
        }
        int rawTotal = entries.stream().mapToInt(Map.Entry::getValue).sum();
        boolean totalSuppressed;
        String totalReason = null;
        if (!includeTotal) {
            totalSuppressed = true;
            totalReason = "TOTAL_NOT_REQUESTED";
        } else if (rawTotal < k) {
            totalSuppressed = true;
            totalReason = "TOTAL_SMALL_GROUP";
        } else if (hidden > 0 && hidden < k) {
            totalSuppressed = true;
            totalReason = "TOTAL_COMPLEMENTARY";
        } else {
            totalSuppressed = false;
        }
        return new DisclosureResult(outcomes,
                totalSuppressed ? null : rawTotal,
                totalSuppressed, totalReason,
                rawTotal, published.size(), suppressed.size(), k);
    }
}
