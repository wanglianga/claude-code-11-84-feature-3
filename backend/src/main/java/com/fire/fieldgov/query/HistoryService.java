package com.fire.fieldgov.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fire.fieldgov.db.DbJson;
import com.fire.fieldgov.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史查询复现：按当时保存的查询与版本快照（目录版本 + asOf + 契约版本）重新编译，
 * 并与存档结果逐组比对。旧表单本身不被改写——复现得到的是“当时口径下的答案”。
 */
@Service
public class HistoryService {

    private final JdbcTemplate jdbc;
    private final QueryCompiler compiler;
    private final ObjectMapper om = DbJson.OM;

    public HistoryService(JdbcTemplate jdbc, QueryCompiler compiler) {
        this.jdbc = jdbc;
        this.compiler = compiler;
    }

    public List<Map<String, Object>> listMine() {
        return jdbc.queryForList(
                "SELECT query_id, username, purpose, query_json, created_at FROM query_history " +
                        "WHERE username=? ORDER BY query_id DESC", CurrentUser.get().username())
                .stream().map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>(row);
                    m.put("query_json", DbJson.toMap(row.get("query_json")));
                    return m;
                }).toList();
    }

    public Map<String, Object> get(long queryId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM query_history WHERE query_id=? AND username=?",
                queryId, CurrentUser.get().username());
        if (rows.isEmpty()) throw new com.fire.fieldgov.web.GovernanceException("查询不存在或无权查看");
        Map<String, Object> row = rows.get(0);
        return Map.of(
                "queryId", row.get("query_id"),
                "createdAt", row.get("created_at").toString(),
                "query", DbJson.toMap(row.get("query_json")),
                "storedResult", DbJson.toMap(row.get("result_json")),
                "snapshot", DbJson.toMap(row.get("version_snapshot")));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> replay(long queryId) {
        Map<String, Object> record = get(queryId);
        Map<String, Object> queryMap = (Map<String, Object>) record.get("query");
        Map<String, Object> snapshot = (Map<String, Object>) record.get("snapshot");
        QueryRequest req = om.convertValue(queryMap, QueryRequest.class);
        // 强制按快照版本与 asOf 复现，忽略请求中可能被改动的版本
        QueryRequest pinned = new QueryRequest(
                req.purpose(),
                (String) snapshot.get("asOf"),
                (Integer) snapshot.get("catalogVersion"),
                req.districts(), req.filters(), req.groupBy(), req.includeTotal());
        Map<String, Object> rerun = compiler.compile(pinned);
        Map<String, Object> stored = (Map<String, Object>) record.get("storedResult");

        List<Map<String, Object>> compare = new ArrayList<>();
        Map<String, Object> storedDisclosure = stored.containsKey("disclosure")
                ? (Map<String, Object>) stored.get("disclosure") : null;
        List<Map<String, Object>> storedGroups = storedDisclosure == null
                ? List.of() : (List<Map<String, Object>>) storedDisclosure.get("groups");
        // 编译器输出中的 GroupOutcome 是 record，先经 Jackson 转成 Map 再按键读取
        List<Map<String, Object>> rerunGroups = ((List<?>) ((Map<String, Object>) rerun.get("disclosure")).get("groups"))
                .stream().map(g -> om.convertValue(g, Map.class)).map(m -> (Map<String, Object>) m).toList();
        Map<String, Object> storedByGroup = new LinkedHashMap<>();
        for (Map<String, Object> g : storedGroups) storedByGroup.put(g.get("group").toString(), g);
        boolean match = true;
        for (Map<String, Object> g : rerunGroups) {
            Map<String, Object> old = (Map<String, Object>) storedByGroup.get(g.get("group"));
            boolean same = old != null
                    && java.util.Objects.equals(old.get("count"), g.get("count"))
                    && java.util.Objects.equals(old.get("suppressed"), g.get("suppressed"));
            match &= same;
            // 抑制组 count 为 null，Map.of 不允许空值，使用 HashMap
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", g.get("group"));
            row.put("storedCount", old == null ? null : old.get("count"));
            row.put("replayCount", g.get("count"));
            row.put("storedSuppressed", old == null ? null : old.get("suppressed"));
            row.put("replaySuppressed", g.get("suppressed"));
            row.put("same", same);
            compare.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queryId", queryId);
        out.put("pinnedRequest", pinned);
        out.put("snapshot", snapshot);
        out.put("groupComparison", compare);
        out.put("reproduceMatch", match);
        out.put("replayResult", rerun);
        return out;
    }
}
