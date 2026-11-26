package com.medwaste.sim;

import com.medwaste.common.ApiException;
import com.medwaste.common.Db;
import com.medwaste.common.Views;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMethod;

import java.math.BigDecimal;
import java.util.Map;

/** 虚构硬件模拟器：称重仪读数注入/读取、处置设备列表。回执模拟在 DisposalController。 */
@RestController
@RequestMapping("/api/sim")
public class SimController {
    private final Db db;

    public SimController(Db db) {
        this.db = db;
    }

    @GetMapping("/scales")
    public Object scales() {
        return Views.list(db.list("SELECT * FROM sim_devices WHERE device_type='SCALE' ORDER BY code"));
    }

    @GetMapping("/disposal-devices")
    public Object disposalDevices() {
        return Views.list(db.list("SELECT * FROM sim_devices WHERE device_type='DISPOSAL' ORDER BY code"));
    }

    public record ReadingReq(BigDecimal readingKg) {}

    @RequestMapping(value = "/scales/{code}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public Object setReading(@PathVariable String code, @RequestBody ReadingReq req) {
        int n = db.update("UPDATE sim_devices SET reading_kg=?, updated_at=now() WHERE code=? AND device_type='SCALE'",
                req.readingKg(), code);
        if (n == 0) throw ApiException.notFound("DEVICE_NOT_FOUND", "称重仪不存在: " + code);
        return Views.of(db.one("SELECT * FROM sim_devices WHERE code=?", code));
    }

    @PostMapping("/scales/{code}/reading")
    public Object setReadingPost(@PathVariable String code, @RequestBody ReadingReq req) {
        return setReading(code, req);
    }

    /** 仪表“读数”：返回当前模拟稳定读数。 */
    @GetMapping("/scales/{code}/read")
    public Map<String, Object> read(@PathVariable String code) {
        Map<String, Object> d = Views.of(db.one(
                "SELECT code, name, location, reading_kg, updated_at AS read_at FROM sim_devices WHERE code=? AND device_type='SCALE'",
                code));
        d.put("unit", "kg");
        d.put("stable", true);
        return d;
    }
}
