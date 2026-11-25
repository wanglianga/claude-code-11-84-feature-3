package com.fire.fieldgov.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MappingEngineTest {

    private final MappingEngine engine = new MappingEngine(null);

    @Test
    void cmToMmTransform() {
        assertEquals("12", engine.applyTransform("x*10", "1.2"));   // 乙区 1.2cm -> 12mm
        assertEquals("20", engine.applyTransform("x*10", "2.0"));
        assertEquals("15", engine.applyTransform("x*1", "15"));
    }

    @Test
    void unknownFormulaRejected() {
        assertThrows(IllegalArgumentException.class, () -> engine.applyTransform("x^2", "3"));
    }
}
