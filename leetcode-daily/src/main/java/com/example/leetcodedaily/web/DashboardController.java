package com.example.leetcodedaily.web;

import com.example.leetcodedaily.model.StreakData;
import com.example.leetcodedaily.service.LeetCodeService;
import com.example.leetcodedaily.service.StreakService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final LeetCodeService leetCodeService;
    private final StreakService streakService;

    public DashboardController(LeetCodeService leetCodeService, StreakService streakService) {
        this.leetCodeService = leetCodeService;
        this.streakService = streakService;
    }

    @GetMapping("/")
    public String dashboard(Model model,
                            @RequestParam(required = false, defaultValue = "") String difficulty) {
        model.addAttribute("difficulty", difficulty);

        StreakData streak;
        try {
            streak = streakService.getStreak();
        } catch (Exception e) {
            log.warn("Could not read streak: {}", e.getMessage());
            streak = new StreakData();
        }
        model.addAttribute("streak", streak);

        try {
            model.addAttribute("picks", leetCodeService.getPicks(difficulty, 2));
        } catch (Exception e) {
            model.addAttribute("picks", java.util.List.of());
            model.addAttribute("error", e.getMessage());
        }

        return "index";
    }

    @PostMapping("/done")
    public String markDone(RedirectAttributes ra,
                           @RequestParam(required = false, defaultValue = "") String difficulty) {
        streakService.markDone();
        ra.addFlashAttribute("message", "Session logged! Keep the streak going.");
        return "redirect:/?difficulty=" + difficulty;
    }
}
