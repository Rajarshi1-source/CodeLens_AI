package com.storefront.report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ReportExporter {

    private final Map<String, Report> store;

    public ReportExporter(Map<String, Report> store) {
        this.store = store;
    }

    public Report findReport(String id) {
        return store.get(id);
    }
}
