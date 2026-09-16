package com.raaspal.robotrecommendation.casereport.brand;

import com.raaspal.robotrecommendation.casereport.entity.CaseTicket;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether a stored ticket belongs to a brand.
 *
 * <p>The same two signals the monday filter uses, applied to the rows already in
 * {@code case_ticket}: the model label, or a name fragment. The daily open-group sync
 * stores every open delivery ticket regardless of brand, so the analytics cannot simply
 * take every row on the board - they have to re-apply the rule here.
 */
public final class BrandMatcher {

    private final List<String> models;
    private final List<String> nameTerms;

    public BrandMatcher(BrandTicketProperties.Brand brand) {
        this.models = brand.getModels().stream().map(BrandMatcher::norm).toList();
        this.nameTerms = brand.getNameTerms().stream().map(BrandMatcher::norm).toList();
    }

    public boolean matches(CaseTicket ticket) {
        return matches(ticket.getRobotModel(), ticket.getItemName());
    }

    public boolean matches(String robotModel, String itemName) {
        if (robotModel != null && models.contains(norm(robotModel))) return true;
        if (itemName == null) return false;
        String name = norm(itemName);
        return nameTerms.stream().anyMatch(name::contains);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
