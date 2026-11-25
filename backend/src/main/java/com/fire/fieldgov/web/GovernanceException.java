package com.fire.fieldgov.web;

/** 业务规则违反（含映射不等价拒绝、权限拒绝、语义未决等），映射为 HTTP 422。 */
public class GovernanceException extends RuntimeException {
    public GovernanceException(String message) { super(message); }
}
