package com.raaspal.robotrecommendation.casereport.customer;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A case's customer from its robot: by serial, then by whole site name. Made-up robots and sites. */
class RobotCustomersTest {

    private static final RobotCustomers.Directory ROBOTS = RobotCustomers.build(List.of(
            new RobotCustomers.RobotRow("IFS : Riverside Clinic Tower", "AB12-0001-X", null, null),
            new RobotCustomers.RobotRow("MK สาขา Central Lakeside", "MK-7 , mk-8", "Lakeside Mall", null),
            new RobotCustomers.RobotRow("PCS : Northgate Logistics Hub", null, null, null),
            new RobotCustomers.RobotRow("PCS : Makro สาขา Eastfield", "GS401-0099-ZZZ", null, null),
            new RobotCustomers.RobotRow("Someone Else Office", "OTHER-1", null, null)), Instant.now());

    @Test
    void aSerialNamesTheCustomerWhateverTheTicketSays() {
        assertThat(RobotCustomers.match(ROBOTS, ref(" ab12-0001-x ", "Riverside", null)))
                .isEqualTo(new RobotCustomers.Match(RobotCustomers.IFS, RobotCustomers.Via.SERIAL));
        // Several serials in one cell, any of them.
        assertThat(RobotCustomers.match(ROBOTS, ref("MK8", null, null))).isNull();
        assertThat(RobotCustomers.match(ROBOTS, ref("x1, MK-8", null, null)).customer()).isEqualTo(RobotCustomers.MK);
        // PCS and Makro on one robot is Makro: Makro is PCS's customer.
        assertThat(RobotCustomers.match(ROBOTS, ref("GS401-0099-ZZZ", null, null)).customer()).isEqualTo(RobotCustomers.MAKRO);
    }

    /** Only a whole site name counts; a word or two in common does not. */
    @Test
    void aSiteMatchesOnlyWhole() {
        assertThat(RobotCustomers.match(ROBOTS, ref(null, null, "สาขา Northgate Logistics Hub")))
                .isEqualTo(new RobotCustomers.Match(RobotCustomers.PCS, RobotCustomers.Via.SITE));
        // Words in common, but not one name: "Riverside … Tower" is not "Riverside Clinic Tower".
        assertThat(RobotCustomers.match(ROBOTS, ref(null, null, "Riverside Tower"))).isNull();
        // One whole name inside the other is the same site.
        assertThat(RobotCustomers.match(ROBOTS, ref(null, null, "Riverside Clinic")).customer()).isEqualTo(RobotCustomers.IFS);
        assertThat(RobotCustomers.match(ROBOTS, ref(null, null, "Hub"))).isNull();
        // A robot that belongs to no pending-tab customer matches nothing.
        assertThat(RobotCustomers.match(ROBOTS, ref("OTHER-1", "Someone Else Office", null))).isNull();
    }

    @Test
    void customerWordsAreWholeWords() {
        assertThat(RobotCustomers.customerOf("PCSX depot")).isNull();
        assertThat(RobotCustomers.customerOf("Yayoi สาขา 3")).isEqualTo(RobotCustomers.MK);
        assertThat(RobotCustomers.customerOf("MaKro Hatyai")).isEqualTo(RobotCustomers.MAKRO);
        assertThat(RobotCustomers.customerOf("IFS and MK")).isNull();
    }

    private static RobotCustomers.CaseRef ref(String serial, String project, String branch) {
        return new RobotCustomers.CaseRef("k", serial, project, branch);
    }
}
