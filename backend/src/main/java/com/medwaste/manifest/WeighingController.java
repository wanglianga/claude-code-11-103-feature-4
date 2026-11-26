package com.medwaste.manifest;

import com.medwaste.auth.AppUser;
import com.medwaste.web.CurrentUsers;
import com.medwaste.weight.WeighingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WeighingController {
    private final WeighingService weighing;
    private final PalletService pallets;

    public WeighingController(WeighingService weighing, PalletService pallets) {
        this.weighing = weighing;
        this.pallets = pallets;
    }

    public record BoxWeighReq(String manifestCode, String boxQr, BigDecimal grossKg, String deviceCode) {}
    public record PalletWeighReq(Long loadId, BigDecimal grossKg, String deviceCode) {}
    public record ReviseReq(BigDecimal grossKg, String reason) {}
    public record ScanReq(String boxQr) {}

    @PostMapping("/weighing/handover-box")
    public Map<String, Object> handoverBox(@RequestBody BoxWeighReq req, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        return weighing.proposeHandoverBox(req.manifestCode(), req.boxQr(), req.grossKg(), req.deviceCode(), u);
    }

    @PostMapping("/weighing/pallet")
    public Map<String, Object> pallet(@RequestBody PalletWeighReq req, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        return weighing.proposePallet(req.loadId(), req.grossKg(), req.deviceCode(), u);
    }

    @PostMapping("/weighing/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable long id, HttpServletRequest request) {
        return weighing.confirm(id, CurrentUsers.get(request));
    }

    @PostMapping("/weighing/{id}/revise")
    public Map<String, Object> revise(@PathVariable long id, @RequestBody ReviseReq req,
                                      HttpServletRequest request) {
        return weighing.revise(id, req.grossKg(), req.reason(), CurrentUsers.get(request));
    }

    @PostMapping("/pallet-loads/{id}/scan")
    public Map<String, Object> scanPallet(@PathVariable long id, @RequestBody ScanReq req) {
        return pallets.scanBox(id, req.boxQr());
    }

    @GetMapping("/pallet-loads/{id}")
    public Map<String, Object> load(@PathVariable long id) {
        return pallets.loadDetail(id);
    }

    @PostMapping("/manifests/{code}/plant-weigh")
    public Map<String, Object> plantWeigh(@PathVariable String code,
                                          @RequestBody WeighingService.PlantWeighReq req,
                                          HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        return weighing.plantWeigh(code, req, u);
    }

    @PostMapping("/discrepancies/{code}/supplement-weigh")
    public Map<String, Object> supplement(@PathVariable String code,
                                          @RequestBody WeighingService.SupplementReq req,
                                          HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        return weighing.supplement(code, req, u);
    }
}
