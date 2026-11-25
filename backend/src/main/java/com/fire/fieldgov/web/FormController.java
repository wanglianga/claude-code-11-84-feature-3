package com.fire.fieldgov.web;

import com.fire.fieldgov.form.FormInstanceService;
import com.fire.fieldgov.mapping.EventReplayService;
import com.fire.fieldgov.mapping.MappingEngine;
import com.fire.fieldgov.model.FormInstance;
import com.fire.fieldgov.model.MappedValue;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 表单实例与逐案映射证据查看（旧表按提交时目录版本解释）。 */
@RestController
@RequestMapping("/api/forms")
public class FormController {

    private final FormInstanceService forms;
    private final MappingEngine mapping;
    private final EventReplayService replay;

    public FormController(FormInstanceService forms, MappingEngine mapping, EventReplayService replay) {
        this.forms = forms;
        this.mapping = mapping;
        this.replay = replay;
    }

    @GetMapping
    public List<FormInstance> list() { return forms.all(); }

    @GetMapping("/{instanceId}")
    public Map<String, Object> detail(@PathVariable String instanceId) {
        FormInstance inst = forms.get(instanceId);
        if (inst == null) throw new GovernanceException("表单实例不存在: " + instanceId);
        List<Map<String, Object>> mappingTrace = new ArrayList<>();
        for (Map.Entry<String, String> e : inst.values().entrySet()) {
            MappedValue mv = mapping.map(inst, e.getKey(), e.getValue());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceFieldId", mv.sourceFieldId());
            row.put("sourceValue", mv.sourceValue());
            row.put("targetFieldId", mv.targetFieldId());
            row.put("targetValue", mv.targetValue());
            row.put("contractId", mv.contractId());
            row.put("contractVersion", mv.contractVersion());
            row.put("transform", mv.transform());
            row.put("equivalent", mv.equivalent());
            row.put("evidence", mv.evidence());
            mappingTrace.add(row);
        }
        return Map.of(
                "instance", inst,
                "mappingTrace", mappingTrace,
                "caseEvents", replay.listEvents(inst.caseId()));
    }

    /** 显式演示“无法证明语义等价则拒绝”。 */
    @PostMapping("/map-check")
    public Map<String, Object> mapCheck(@RequestBody Map<String, String> body) {
        FormInstance inst = forms.get(body.get("instanceId"));
        if (inst == null) throw new GovernanceException("表单实例不存在");
        MappedValue mv = mapping.mapOrReject(inst, body.get("fieldId"), body.get("value"));
        return Map.of("accepted", true, "mapped", mv);
    }
}
