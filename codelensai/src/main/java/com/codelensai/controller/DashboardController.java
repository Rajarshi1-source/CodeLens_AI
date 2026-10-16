package com.codelensai.controller;

import com.codelensai.model.dto.DashboardStats;
import com.codelensai.model.entity.User;
import com.codelensai.security.CurrentUser;
import com.codelensai.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/stats")
    public DashboardStats stats(@CurrentUser User user) {
        return dashboardService.stats(user.getId());
    }
}
