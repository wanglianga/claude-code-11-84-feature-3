package com.fire.fieldgov.web;

import com.fire.fieldgov.policy.AccessPolicyService;
import com.fire.fieldgov.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final JdbcTemplate jdbc;
    private final AccessPolicyService policy;

    public AdminController(JdbcTemplate jdbc, AccessPolicyService policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    @GetMapping("/grants")
    public List<Map<String, Object>> grants() {
        return policy.listGrants(CurrentUser.get().username());
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        if (!"CITY_ADMIN".equals(CurrentUser.get().role()) && !"AUDITOR".equals(CurrentUser.get().role())) {
            throw new GovernanceException("仅市级管理员/审计员可查看审计日志");
        }
        return jdbc.queryForList("SELECT id, username, action, detail, created_at FROM audit_log ORDER BY id DESC LIMIT 200")
                .stream().map(row -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>(row);
                    m.put("detail", com.fire.fieldgov.db.DbJson.toMap(row.get("detail")));
                    return m;
                }).toList();
    }
}
