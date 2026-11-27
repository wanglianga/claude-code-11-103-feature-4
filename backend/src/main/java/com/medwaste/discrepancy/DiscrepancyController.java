package com.medwaste.discrepancy;

import com.medwaste.auth.AppUser;
import com.medwaste.web.CurrentUsers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/discrepancies")
public class DiscrepancyController {
    private final DiscrepancyService service;

    public DiscrepancyController(DiscrepancyService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false, defaultValue = "ALL") String status) {
        return service.list(status);
    }

    @GetMapping("/{code}")
    public Map<String, Object> get(@PathVariable String code) {
        return service.getByCode(code);
    }

    public record ResolveReq(String resolutionType, String note) {}

    @PostMapping("/{code}/resolve")
    public Map<String, Object> resolve(@PathVariable String code, @RequestBody ResolveReq req,
                                       HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        return service.resolve(code, req.resolutionType(), req.note(), u.displayName());
    }
}
