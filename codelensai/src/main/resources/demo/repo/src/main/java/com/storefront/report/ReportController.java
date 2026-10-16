package com.storefront.report;

public class ReportController {

    private final ReportExporter exporter;

    public ReportController(ReportExporter exporter) {
        this.exporter = exporter;
    }

    public String show(String id) {
        return exporter.findReport(id).render();
    }
}
