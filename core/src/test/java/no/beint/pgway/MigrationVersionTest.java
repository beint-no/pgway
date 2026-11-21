package no.beint.pgway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MigrationVersionTest {

    @Test
    void numericOrderingAcrossSegments() {
        assertTrue(MigrationVersion.parse("1").compareTo(MigrationVersion.parse("2")) < 0);
        assertTrue(MigrationVersion.parse("2").compareTo(MigrationVersion.parse("10")) < 0);
        assertTrue(MigrationVersion.parse("1.2").compareTo(MigrationVersion.parse("1.10")) < 0);
        assertTrue(MigrationVersion.parse("1.9.9").compareTo(MigrationVersion.parse("2.0")) < 0);
        assertTrue(MigrationVersion.parse("202611200001").compareTo(MigrationVersion.parse("202611210001")) < 0);
        assertTrue(
            MigrationVersion.parse("99999999999999999999999999")
                .compareTo(MigrationVersion.parse("100000000000000000000000000")) < 0
        );
    }

    @Test
    void normalizesUnderscoresAndCanonicalEquality() {
        MigrationVersion v1 = MigrationVersion.parse("1");
        MigrationVersion v01 = MigrationVersion.parse("01");
        MigrationVersion v1dot0 = MigrationVersion.parse("1.0");
        MigrationVersion v1under0 = MigrationVersion.parse("1_0_0");

        assertEquals(0, v1.compareTo(v01));
        assertEquals(0, v1.compareTo(v1dot0));
        assertEquals(0, v1.compareTo(v1under0));
        assertEquals(v1, v01);
        assertEquals(v1, v1dot0);
        assertEquals(v1, v1under0);
        assertEquals(v1.hashCode(), v1dot0.hashCode());

        assertEquals("1.2", MigrationVersion.parse("1_2").value());
        assertNotEquals(MigrationVersion.parse("1.2"), MigrationVersion.parse("1.2.1"));
    }

    @Test
    void rejectsInvalidVersions() {
        assertThrows(IllegalArgumentException.class, () -> MigrationVersion.parse(""));
        assertThrows(IllegalArgumentException.class, () -> MigrationVersion.parse("1."));
        assertThrows(IllegalArgumentException.class, () -> MigrationVersion.parse(".1"));
        assertThrows(IllegalArgumentException.class, () -> MigrationVersion.parse("1..2"));
        assertThrows(IllegalArgumentException.class, () -> MigrationVersion.parse("1a"));
        assertThrows(IllegalArgumentException.class, () -> MigrationVersion.parse("-1"));
        assertThrows(IllegalArgumentException.class, () -> MigrationVersion.parse("1.2-beta"));
    }
}
