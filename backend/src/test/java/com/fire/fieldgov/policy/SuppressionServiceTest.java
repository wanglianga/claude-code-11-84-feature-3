package com.fire.fieldgov.policy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
class SuppressionServiceTest {

    private SuppressionService service() {
        SuppressionService s = new SuppressionService();
        org.springframework.test.util.ReflectionTestUtils.setField(s, "threshold", 4);
        return s;
    }

    @Test
    void smallGroupsAreSuppressedAndRawCountsNeverLeak() {
        Map<String, Integer> raw = new LinkedHashMap<>();
        raw.put("BING", 3); // 小于阈值
        SuppressionService.DisclosureResult r = service().apply(raw, true);
        assertTrue(r.groups().get(0).suppressed());
        assertNull(r.groups().get(0).count());
        assertTrue(r.totalSuppressed()); // 总数 3 也不能发布
    }

    @Test
    void complementarySuppressionBlocksSubtractionAttack() {
        // JIA=6 可发布；BING=3 小组；hidden=3 可由 总数-6 推回 → 必须把 JIA 也互补抑制。
        // 抑制后隐藏桶=9（≥阈值），总数已无法反推任一小组，方可发布总数。
        Map<String, Integer> raw = new LinkedHashMap<>();
        raw.put("JIA", 6);
        raw.put("BING", 3);
        SuppressionService.DisclosureResult r = service().apply(raw, true);
        assertEquals(2, r.suppressedGroupCount());
        assertTrue(r.groups().stream().allMatch(SuppressionService.GroupOutcome::suppressed));
        assertTrue(r.groups().stream().allMatch(g -> g.count() == null));
        assertEquals(9, r.total());
        assertFalse(r.totalSuppressed());
    }

    @Test
    void healthyGroupsPublishWhenHiddenBucketIsItselfLargeEnough() {
        Map<String, Integer> raw = new LinkedHashMap<>();
        raw.put("JIA", 5);
        raw.put("YI", 5);
        raw.put("BING", 4);
        SuppressionService.DisclosureResult r = service().apply(raw, true);
        assertEquals(0, r.suppressedGroupCount());
        assertEquals(14, r.total());
    }
}
