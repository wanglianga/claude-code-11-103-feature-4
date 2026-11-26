package com.medwaste.container;

import com.medwaste.auth.AppUser;
import com.medwaste.auth.AuthService;
import com.medwaste.web.CurrentUsers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ContainerController {
    private final ContainerService containers;

    public ContainerController(ContainerService containers) {
        this.containers = containers;
    }

    // ---------- 箱 ----------
    @GetMapping("/boxes")
    public List<Map<String, Object>> boxes() {
        return containers.listBoxes();
    }

    @GetMapping("/boxes/{code}")
    public Map<String, Object> box(@PathVariable String code) {
        return containers.boxDetail(code);
    }

    public record CreateBoxReq(String code) {}

    @PostMapping("/boxes")
    public Map<String, Object> createBox(@RequestBody CreateBoxReq req, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "HOSPITAL", "ADMIN");
        return containers.createBox(req.code(), u);
    }

    public record StartRoundReq(BigDecimal actualTareKg, String deviceCode) {}

    @PostMapping("/boxes/{code}/rounds")
    public Map<String, Object> startRound(@PathVariable String code,
                                          @RequestBody StartRoundReq req,
                                          HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "HOSPITAL", "ADMIN");
        return containers.startRound(code, req.actualTareKg(), req.deviceCode(), u);
    }

    public record CleanReq(BigDecimal newTareKg, String deviceCode) {}

    @PostMapping("/boxes/{code}/clean")
    public Map<String, Object> clean(@PathVariable String code,
                                     @RequestBody CleanReq req,
                                     HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "HOSPITAL", "ADMIN");
        return containers.cleanBox(code, req.newTareKg(), req.deviceCode(), u);
    }

    // ---------- 袋 / 封口码 ----------
    @GetMapping("/bags")
    public List<Map<String, Object>> bags() {
        return containers.listBags();
    }

    @GetMapping("/bags/{sealCode}")
    public Map<String, Object> bag(@PathVariable String sealCode) {
        return containers.bagDetail(sealCode);
    }

    public record CreateBagReq(String sealCode, String categoryCode) {}

    @PostMapping("/bags")
    public Map<String, Object> createBag(@RequestBody CreateBagReq req, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "HOSPITAL", "ADMIN");
        return containers.createBag(req.sealCode(), req.categoryCode(), u);
    }

    public record ClaimReq(String boxQr, String sealCode) {}

    /** 扫码领取：收运员/医院端都可能扫；登录即可，规则在 Service 强校验。 */
    @PostMapping("/scan/claim-bag")
    public Map<String, Object> claim(@RequestBody ClaimReq req, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        return containers.claimBag(req.boxQr(), req.sealCode(), u);
    }

    @DeleteMapping("/box-rounds/{roundId}/bags/{sealCode}")
    public Map<String, Object> removeBag(@PathVariable long roundId, @PathVariable String sealCode,
                                         HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "HOSPITAL", "ADMIN");
        return containers.removeBag(roundId, sealCode);
    }

    public record SealReq(BigDecimal grossKg, String deviceCode) {}

    @PostMapping("/box-rounds/{roundId}/seal")
    public Map<String, Object> seal(@PathVariable long roundId, @RequestBody SealReq req,
                                    HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "HOSPITAL", "ADMIN");
        return containers.seal(roundId, req.grossKg(), req.deviceCode(), u);
    }

    // ---------- 托盘 ----------
    @GetMapping("/pallets")
    public List<Map<String, Object>> pallets() {
        return containers.listPallets();
    }

    public record PalletReq(String code, BigDecimal tareKg) {}

    @PostMapping("/pallets")
    public Map<String, Object> createPallet(@RequestBody PalletReq req, HttpServletRequest request) {
        AppUser u = CurrentUsers.get(request);
        AuthService.requireRole(u, "HOSPITAL", "PLANT", "ADMIN");
        return containers.createPallet(req.code(), req.tareKg(), u);
    }

    @PatchMapping("/pallets/{code}/tare")
    public Map<String, Object> tare(@PathVariable String code, @RequestBody PalletReq req) {
        return containers.updatePalletTare(code, req.tareKg());
    }
}
