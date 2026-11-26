package com.medwaste.manifest;

import com.medwaste.auth.AppUser;
import com.medwaste.auth.AuthService;
import com.medwaste.web.CurrentUsers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/manifests")
public class ManifestController {
    private final ManifestService manifests;
    private final PalletService pallets;

    public ManifestController(ManifestService manifests, PalletService pallets) {
        this.manifests = manifests;
        this.pallets = pallets;
    }

    public record CreateReq(String vehicleCode, String hospitalOrgCode) {}
    public record ScanReq(String scanCode) {}
    public record VehicleReq(String vehicleCode) {}
    public record PalletReq(String palletCode, List<String> boxQrs) {}

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateReq req, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "TRANSPORTER");
        return manifests.create(req.vehicleCode(), req.hospitalOrgCode(), u);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          HttpServletRequest request) {
        return manifests.listFor(CurrentUsers.get(request), status);
    }

    @GetMapping("/{code}")
    public Map<String, Object> detail(@PathVariable String code) {
        return manifests.detail(code);
    }

    @PostMapping("/{code}/vehicle")
    public Map<String, Object> vehicle(@PathVariable String code, @RequestBody VehicleReq req,
                                       HttpServletRequest request) {
        CurrentUsers.get(request);
        return manifests.assignVehicle(code, req.vehicleCode());
    }

    @PostMapping("/{code}/scan")
    public Map<String, Object> scan(@PathVariable String code, @RequestBody ScanReq req,
                                    HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "TRANSPORTER");
        return manifests.scanItem(code, req.scanCode(), u);
    }

    @DeleteMapping("/{code}/items/{roundId}")
    public Map<String, Object> remove(@PathVariable String code, @PathVariable long roundId,
                                      HttpServletRequest request) {
        CurrentUsers.get(request);
        return manifests.removeItem(code, roundId);
    }

    @PostMapping("/{code}/submit")
    public Map<String, Object> submit(@PathVariable String code, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "TRANSPORTER");
        return manifests.submit(code);
    }

    @PostMapping("/{code}/handover")
    public Map<String, Object> handover(@PathVariable String code, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "HOSPITAL");
        return manifests.handover(code, u);
    }

    @PostMapping("/{code}/cancel")
    public Map<String, Object> cancel(@PathVariable String code, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        return manifests.cancel(code, u);
    }

    @PostMapping("/{code}/arrive")
    public Map<String, Object> arrive(@PathVariable String code, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "TRANSPORTER");
        return manifests.arrive(code, u);
    }

    @PostMapping("/{code}/return-to-transit")
    public Map<String, Object> returnToTransit(@PathVariable String code) {
        return manifests.returnToTransitForbidden(code);
    }

    @PostMapping("/{code}/intake")
    public Map<String, Object> intake(@PathVariable String code,
                                       @RequestBody ManifestService.IntakeReq req,
                                       HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "PLANT");
        return manifests.intake(code, req, u);
    }

    @PostMapping("/{code}/sign")
    public Map<String, Object> sign(@PathVariable String code,
                                     @RequestBody(required = false) ManifestService.SignReq req,
                                     HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "PLANT");
        return manifests.sign(code, req == null ? new ManifestService.SignReq(null) : req, u);
    }

    @PostMapping("/{code}/return-empties")
    public Map<String, Object> returnEmpties(@PathVariable String code, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "TRANSPORTER", "PLANT");
        return manifests.returnEmpties(code, u);
    }

    // ---------- 封托 ----------
    @PostMapping("/{code}/pallet-load")
    public Map<String, Object> palletLoad(@PathVariable String code, @RequestBody PalletReq req,
                                          HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "TRANSPORTER", "HOSPITAL");
        return pallets.createLoad(code, req.palletCode(), req.boxQrs(), u);
    }
}
