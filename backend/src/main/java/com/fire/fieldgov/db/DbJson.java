package com.fire.fieldgov.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JSONB / PG 数组与 Java 对象之间的转换工具。 */
public final class DbJson {
    public static final ObjectMapper OM = new ObjectMapper();

    private DbJson() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object pg) {
        if (pg == null) return Map.of();
        try {
            String text = pg instanceof PGobject pgo ? pgo.getValue() : pg.toString();
            return OM.readValue(text, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 解析失败: " + e.getMessage(), e);
        }
    }

    public static List<String> toStringList(Object pgArray) {
        if (pgArray == null) return List.of();
        try {
            Object arr = pgArray instanceof java.sql.Array sqlArr ? sqlArr.getArray() : pgArray;
            if (arr instanceof Object[] objects) {
                List<String> out = new ArrayList<>();
                for (Object o : objects) if (o != null) out.add(o.toString());
                return out;
            }
            return List.of();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> toList(Object pg) {
        if (pg == null) return List.of();
        try {
            String text = pg instanceof PGobject pgo ? pgo.getValue() : pg.toString();
            return OM.readValue(text, List.class);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 解析失败: " + e.getMessage(), e);
        }
    }

    public static String json(Object o) {
        try { return OM.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
