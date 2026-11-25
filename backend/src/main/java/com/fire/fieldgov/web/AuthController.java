package com.fire.fieldgov.web;

import com.fire.fieldgov.security.CurrentUser;
import com.fire.fieldgov.security.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbc;
    private static final String HMAC_SALT = "fire-field-gov-demo-salt";

    public AuthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) throws Exception {
        String username = body.get("username");
        String password = body.get("password");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT username, display_name, role, home_district, clearance, allowed_purposes, password " +
                        "FROM app_user WHERE username=?", username);
        if (rows.isEmpty() || !rows.get(0).get("password").equals(password)) {
            throw new GovernanceException("用户名或密码错误");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_SALT.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> r = rows.get(0);
        java.util.List<String> purposes = com.fire.fieldgov.db.DbJson.toStringList(r.get("allowed_purposes"));
        return Map.of(
                "token", username + ":" + sig,
                "user", Map.of(
                        "username", r.get("username"),
                        "displayName", r.get("display_name"),
                        "role", r.get("role"),
                        "homeDistrict", r.get("home_district") == null ? "" : r.get("home_district"),
                        "clearance", r.get("clearance"),
                        "purposes", purposes));
    }

    @GetMapping("/me")
    public UserContext me() { return CurrentUser.get(); }
}
