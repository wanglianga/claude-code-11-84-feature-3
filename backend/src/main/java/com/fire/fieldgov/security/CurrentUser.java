package com.fire.fieldgov.security;

public final class CurrentUser {
    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private CurrentUser() {}

    public static void set(UserContext user) { HOLDER.set(user); }
    public static UserContext get() { return HOLDER.get(); }
    public static void clear() { HOLDER.remove(); }
}
