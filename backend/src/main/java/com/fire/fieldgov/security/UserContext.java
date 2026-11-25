package com.fire.fieldgov.security;

import java.util.List;

public record UserContext(
        String username,
        String displayName,
        String role,
        String homeDistrict,
        String clearance,
        List<String> purposes) {

    public boolean isCityWide() {
        return homeDistrict == null || homeDistrict.isBlank();
    }

    public boolean hasPurpose(String purpose) {
        return purposes != null && purposes.contains(purpose);
    }
}
