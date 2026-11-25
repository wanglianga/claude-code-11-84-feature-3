package com.fire.fieldgov.web;

import com.fire.fieldgov.governance.ExtensionGovernanceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 新隐患字段治理：两条路线的申请、批准（可回溯生效）、驳回。 */
@RestController
@RequestMapping("/api/governance")
public class GovernanceController {

    private final ExtensionGovernanceService governance;

    public GovernanceController(ExtensionGovernanceService governance) { this.governance = governance; }

    @GetMapping("/extensions")
    public List<Map<String, Object>> list() { return governance.listExtensions(); }

    @PostMapping("/extensions/{requestId}/approve")
    public Map<String, Object> approve(@PathVariable String requestId,
                                       @RequestBody(required = false) Map<String, String> body) {
        String retro = body == null ? null : body.get("retroactiveFrom");
        return governance.approve(requestId, retro);
    }

    @PostMapping("/extensions/{requestId}/reject")
    public Map<String, Object> reject(@PathVariable String requestId,
                                      @RequestBody(required = false) Map<String, String> body) {
        return governance.reject(requestId, body == null ? null : body.get("reason"));
    }
}
