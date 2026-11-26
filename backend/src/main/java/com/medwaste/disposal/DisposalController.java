package com.medwaste.disposal;

import com.medwaste.auth.AppUser;
import com.medwaste.auth.AuthService;
import com.medwaste.web.CurrentUsers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DisposalController {
    private final DisposalService disposal;

    public DisposalController(DisposalService disposal) {
        this.disposal = disposal;
    }

    public record BatchReq(List<String> manifestCodes) {}
    public record PerformReq(String deviceCode, String method) {}
    public record InvalidateReq(String reason) {}
    public record HandleReq(String note) {}

    @GetMapping("/mandates")
    public List<Map<String, Object>> mandates() {
        return disposal.mandates();
    }

    @GetMapping("/disposal-batches")
    public List<Map<String, Object>> batches() {
        return disposal.batches();
    }

    @GetMapping("/disposal-batches/{code}")
    public Map<String, Object> batch(@PathVariable String code) {
        return disposal.batch(code);
    }

    @PostMapping("/disposal-batches")
    public Map<String, Object> createBatch(@RequestBody BatchReq req, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "PLANT");
        return disposal.createBatch(req.manifestCodes(), u);
    }

    @PostMapping("/disposal-batches/{code}/perform")
    public Map<String, Object> perform(@PathVariable String code, @RequestBody PerformReq req,
                                        HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "PLANT");
        return disposal.perform(code, req.deviceCode(), req.method(), u);
    }

    @PostMapping("/disposal-batches/{code}/issue-receipt")
    public Map<String, Object> issue(@PathVariable String code, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "PLANT");
        return disposal.issueReceipt(code, u);
    }

    @GetMapping("/receipts")
    public List<Map<String, Object>> receipts(@RequestParam(required = false) String batchCode) {
        return disposal.receipts(batchCode);
    }

    @PostMapping("/receipts/{code}/withdraw")
    public Map<String, Object> withdraw(@PathVariable String code, @RequestBody(required = false) InvalidateReq req,
                                        HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "PLANT", "ADMIN");
        return disposal.invalidateReceipt(code, false, req == null ? null : req.reason(), u);
    }

    @PostMapping("/receipts/{code}/lose")
    public Map<String, Object> lose(@PathVariable String code, @RequestBody(required = false) InvalidateReq req,
                                    HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "PLANT", "ADMIN");
        return disposal.invalidateReceipt(code, true, req == null ? null : req.reason(), u);
    }

    @GetMapping("/review-tasks")
    public List<Map<String, Object>> tasks(@RequestParam(required = false) String status) {
        return disposal.reviewTasks(status);
    }

    @PostMapping("/review-tasks/{code}/handle")
    public Map<String, Object> handle(@PathVariable String code, @RequestBody(required = false) HandleReq req,
                                      HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "PLANT", "ADMIN");
        return disposal.handleReview(code, req == null ? null : req.note(), u);
    }
}
