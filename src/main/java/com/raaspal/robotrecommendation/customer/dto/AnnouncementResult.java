package com.raaspal.robotrecommendation.customer.dto;

import java.util.List;

/** Outcome of an announcement send: how many succeeded/failed, per-customer detail. */
public record AnnouncementResult(int sent, int failed, List<Item> items) {

    public record Item(String customerName, String recipients, boolean ok, String error) {
    }
}
