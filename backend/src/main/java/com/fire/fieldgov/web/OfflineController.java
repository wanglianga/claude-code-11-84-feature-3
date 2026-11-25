package com.fire.fieldgov.web;

import com.fire.fieldgov.offline.OfflineService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/offline")
public class OfflineController {

    private final OfflineService offline;

    public OfflineController(OfflineService offline) { this.offline = offline; }

    /** 离线草案同步：Worker 在本地完成基线检查后提交，服务端再次独立校验并发改动。 */
    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestBody OfflineService.DraftPayload draft) {
        return offline.sync(draft);
    }

    @GetMapping("/drafts")
    public List<Map<String, Object>> drafts() { return offline.listMine(); }

    @PostMapping("/drafts/{draftId}/resolve")
    public Map<String, Object> resolve(@PathVariable String draftId, @RequestBody Map<String, String> body) {
        return offline.resolve(draftId, body.getOrDefault("decision", "REBASE_RESUBMIT"));
    }
}
