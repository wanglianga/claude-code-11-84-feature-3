package com.fire.fieldgov.web;

import com.fire.fieldgov.query.HistoryService;
import com.fire.fieldgov.query.QueryCompiler;
import com.fire.fieldgov.query.QueryRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryCompiler compiler;
    private final HistoryService history;

    public QueryController(QueryCompiler compiler, HistoryService history) {
        this.compiler = compiler;
        this.history = history;
    }

    /** 受控查询编译入口：标准化表达 → 映射/状态/权限 → 披露抑制结果。 */
    @PostMapping("/compile")
    public Map<String, Object> compile(@RequestBody QueryRequest request) {
        return compiler.compile(request);
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history() { return history.listMine(); }

    @GetMapping("/history/{id}")
    public Map<String, Object> historyDetail(@PathVariable long id) { return history.get(id); }

    /** 按保存的版本快照重放历史查询并逐组比对。 */
    @PostMapping("/history/{id}/replay")
    public Map<String, Object> replay(@PathVariable long id) { return history.replay(id); }
}
