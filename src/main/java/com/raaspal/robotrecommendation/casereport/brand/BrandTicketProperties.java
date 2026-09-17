package com.raaspal.robotrecommendation.casereport.brand;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which robot brands get their own ticket analytics, and how each is recognised on
 * the board.
 *
 * <p>The boards have no brand column. A brand is whatever the RE team types: AutoXing
 * robots carry the model labels {@code Zara}, {@code Zara L300}, {@code D150} and, on
 * the tickets where the label was left wrong, a name that starts with {@code ZARA} or
 * {@code D150}. Both signals are configuration because both are conventions - a new
 * model label is a config change, not a release.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.tickets")
public class BrandTicketProperties {

    /**
     * The account's monday web origin, e.g. {@code https://raaspal.monday.com}, for the
     * per-ticket deep links. Blank hides the links rather than guessing the slug.
     */
    private String mondayWebUrl = "";

    private List<Brand> brands = new ArrayList<>();

    public Optional<Brand> find(String key) {
        if (key == null) return Optional.empty();
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        return brands.stream().filter(b -> wanted.equals(b.getKey())).findFirst();
    }

    @Getter
    @Setter
    public static class Brand {

        /** URL slug and JSON id, lower-case: {@code autoxing}. */
        private String key;

        /** Display name: {@code AutoXing}. */
        private String label;

        /** The ticket board this brand's cases are filed on. */
        private String boardId;

        /** The group open cases sit in; anywhere else on the board means closed. */
        private String openGroupId;

        /** The status column holding the robot model label. */
        private String modelColumn;

        /** Model labels, exactly as the dropdown spells them. */
        private List<String> models = new ArrayList<>();

        /** Item-name fragments that mark the brand when the model label is missing or wrong. */
        private List<String> nameTerms = new ArrayList<>();

        public void setKey(String key) {
            this.key = key == null ? null : key.trim().toLowerCase(Locale.ROOT);
        }
    }
}
