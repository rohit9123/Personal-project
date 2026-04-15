package com.example.leetcodedaily.web;

import com.example.leetcodedaily.service.LeetCodeService;
import com.example.leetcodedaily.service.StreakService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class DashboardController {

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
        model.addAttribute("streak", streakService.getStreak());

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
