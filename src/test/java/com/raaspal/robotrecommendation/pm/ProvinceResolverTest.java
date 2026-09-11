package com.raaspal.robotrecommendation.pm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.pm.service.ProvinceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Region and zone are derived from the province, because the PM boards' own region
 * column carries twelve spellings for about seven regions. These tests pin the
 * normalisation that makes that derivation survive real data.
 */
class ProvinceResolverTest {

    private ProvinceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        resolver = new ProvinceResolver(new ObjectMapper());
        Method load = ProvinceResolver.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(resolver);
    }

    @Test
    void loadsEverySeventySevenProvince() {
        assertThat(resolver.provinces()).hasSize(77);
        assertThat(resolver.regions()).containsExactly(
                "BANGKOK", "CENTRAL", "EAST", "WEST", "NORTH", "NORTHEAST", "SOUTH");
    }

    @Test
    void resolvesTheCanonicalThaiName() {
        ProvinceResolver.ResolvedProvince resolved = resolver.resolve("เชียงใหม่");
        assertThat(resolved.province()).isEqualTo("เชียงใหม่");
        assertThat(resolved.region()).isEqualTo("NORTH");
        assertThat(resolved.zone()).isEqualTo("UPPER_NORTH");
    }

    /** "กรุงเทพ" is what the boards actually contain; the official name is longer. */
    @Test
    void resolvesTheAliasesSeenInTheLiveBoards() {
        assertThat(resolver.resolve("กรุงเทพ").province()).isEqualTo("กรุงเทพมหานคร");
        assertThat(resolver.resolve("อยุธยา").province()).isEqualTo("พระนครศรีอยุธยา");
        assertThat(resolver.resolve("โคราช").province()).isEqualTo("นครราชสีมา");
    }

    @Test
    void resolvesEnglishNamesAndIgnoresCase() {
        assertThat(resolver.resolve("phuket").region()).isEqualTo("SOUTH");
        assertThat(resolver.resolve("  Chon Buri ").province()).isEqualTo("ชลบุรี");
    }

    @Test
    void stripsTheProvincePrefixStaffType() {
        assertThat(resolver.resolve("จ.เชียงราย").province()).isEqualTo("เชียงราย");
        assertThat(resolver.resolve("จังหวัดภูเก็ต").province()).isEqualTo("ภูเก็ต");
    }

    /** A whole address still names a province; finding it beats discarding the row. */
    @Test
    void findsAProvinceInsideALongerString() {
        assertThat(resolver.resolve("อ.เมือง จ.ขอนแก่น 40000").province()).isEqualTo("ขอนแก่น");
    }

    /**
     * The important negative case. Roughly half the Delivery contracts have no
     * province, and guessing one would put them confidently in the wrong region.
     */
    @Test
    void refusesToGuess() {
        for (String unknown : new String[]{null, "", "   ", "n/a", "Jakarta"}) {
            ProvinceResolver.ResolvedProvince resolved = resolver.resolve(unknown);
            assertThat(resolved.province()).isEqualTo(ProvinceResolver.UNASSIGNED);
            assertThat(resolved.region()).isEqualTo(ProvinceResolver.UNASSIGNED);
            assertThat(resolved.zone()).isEqualTo(ProvinceResolver.UNASSIGNED);
        }
    }

    /** Bangkok and its five vicinity provinces are one dispatch area, not Central. */
    @Test
    void groupsBangkokWithItsVicinity() {
        assertThat(resolver.resolve("กรุงเทพมหานคร").zone()).isEqualTo("BANGKOK");
        for (String vicinity : new String[]{"นนทบุรี", "ปทุมธานี", "สมุทรปราการ", "สมุทรสาคร", "นครปฐม"}) {
            ProvinceResolver.ResolvedProvince resolved = resolver.resolve(vicinity);
            assertThat(resolved.region()).isEqualTo("BANGKOK");
            assertThat(resolved.zone()).isEqualTo("VICINITY");
        }
    }
}
