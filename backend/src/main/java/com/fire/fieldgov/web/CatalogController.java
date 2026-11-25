package com.fire.fieldgov.web;

import com.fire.fieldgov.catalog.CatalogService;
import com.fire.fieldgov.model.FieldDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalog;
    private final JdbcTemplate jdbc;

    public CatalogController(CatalogService catalog, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    @GetMapping("/versions")
    public List<Map<String, Object>> versions() { return catalog.listCatalogVersions(); }

    /** 字段目录：可按 version + asOf 解释（历史视图），默认当前版本。 */
    @GetMapping("/fields")
    public List<FieldDef> fields(@RequestParam(required = false) Integer version,
                                 @RequestParam(required = false) String asOf) {
        LocalDate date = asOf == null || asOf.isBlank() ? LocalDate.now() : LocalDate.parse(asOf);
        return catalog.defsAsOf(version, date).values().stream().toList();
    }

    /** 同一字段的全部历史版本（演示版本并存与弃用替代）。 */
    @GetMapping("/fields/{fieldId}/history")
    public List<FieldDef> fieldHistory(@PathVariable String fieldId) {
        return catalog.allFieldDefs().stream().filter(d -> d.fieldId().equals(fieldId)).toList();
    }

    @GetMapping("/contracts")
    public List<?> contracts() { return catalog.allContracts(); }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return jdbc.queryForList("SELECT * FROM form_template ORDER BY district, template_id");
    }
}
