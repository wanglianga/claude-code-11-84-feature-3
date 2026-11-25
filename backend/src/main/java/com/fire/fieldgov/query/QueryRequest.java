package com.fire.fieldgov.query;

import java.util.List;

/** 受控查询请求；value 统一以字符串/数字传入，编译期按标准字段值域解释。 */
public record QueryRequest(
        String purpose,
        String asOf,
        Integer catalogVersion,
        List<String> districts,
        List<Filter> filters,
        List<String> groupBy,
        Boolean includeTotal) {

    public record Filter(String field, String op, Object value) {}
}
