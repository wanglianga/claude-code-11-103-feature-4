package com.medwaste.zone;

import com.medwaste.auth.AppUser;
import com.medwaste.web.CurrentUsers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 跨区人员任务与防护用品（feature-4）。
 * 总览 GET /api/zone/board 一次返回人员状态/任务责任/污染轮次/流程卡/库存/可继续办理清单；
 * 其余为派发、进区、转换、独立复核、回执、库存等动作端点。
 */
@RestController
@RequestMapping("/api/zone")
public class ZoneController {
    private final ZoneService service;
    private final ZoneSeed seed;

    public ZoneController(ZoneService service, ZoneSeed seed) {
        this.service = service;
        this.seed = seed;
    }

    // ---------------- 基线 / 总览 / 方案 ----------------

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        return service.reset();
    }

    @GetMapping("/board")
    public Map<String, Object> board() {
        return service.board();
    }

    @GetMapping("/dispatch-plans")
    public Map<String, Object> plans() {
        return service.dispatchPlans();
    }

    @GetMapping("/clock")
    public Map<String, Object> clock() {
        return service.clock();
    }

    @PostMapping("/clock")
    public Map<String, Object> setClock(@RequestBody(required = false) Map<String, Object> body) {
        return service.setClock(body);
    }

    // 便捷只读别名（board 已含全部数据，这里给出资源级入口）
    @GetMapping("/staff")
    public List<Map<String, Object>> staff() {
        return (List<Map<String, Object>>) service.board().get("staff");
    }

    @GetMapping("/staff/{code}")
    public Map<String, Object> staffDetail(@PathVariable String code) {
        return service.staffDetail(code);
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return (List<Map<String, Object>>) service.board().get("tasks");
    }

    @GetMapping("/tasks/{code}")
    public Map<String, Object> taskDetail(@PathVariable String code) {
        return service.taskDetail(code);
    }

    @GetMapping("/rounds")
    public List<Map<String, Object>> rounds() {
        return (List<Map<String, Object>>) service.board().get("rounds");
    }

    @GetMapping("/rounds/{code}")
    public Map<String, Object> roundDetail(@PathVariable String code) {
        return service.roundDetail(code);
    }

    @GetMapping("/ppe")
    public Map<String, Object> ppe() {
        Map<String, Object> b = service.board();
        return Map.of("skus", b.get("skus"), "kits", b.get("kits"), "reservations", b.get("reservations"));
    }

    // ---------------- 任务与派发 ----------------

    @PostMapping("/tasks")
    public Map<String, Object> createTask(@RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest req) {
        AppUser u = CurrentUsers.get(req);
        return service.createTask(body, u);
    }

    @PostMapping("/tasks/{code}/assign")
    public Map<String, Object> assign(@PathVariable String code,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest req) {
        return service.assign(code, body, CurrentUsers.get(req), false);
    }

    /** /dispatch 作为 /assign 的同义路径，便于不同调用习惯。 */
    @PostMapping("/tasks/{code}/dispatch")
    public Map<String, Object> dispatch(@PathVariable String code,
                                        @RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest req) {
        return service.assign(code, body, CurrentUsers.get(req), false);
    }

    /** 领取：从当前模拟时刻起锁定本人/指定人员的可用段。 */
    @PostMapping("/tasks/{code}/claim")
    public Map<String, Object> claim(@PathVariable String code,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest req) {
        return service.assign(code, body, CurrentUsers.get(req), true);
    }

    @PostMapping("/tasks/{code}/start")
    public Map<String, Object> start(@PathVariable String code,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest req) {
        return service.startTask(code, body, CurrentUsers.get(req));
    }

    /** 污染任务“进入污染区”语义别名，内部同一状态机。 */
    @PostMapping("/tasks/{code}/enter")
    public Map<String, Object> enter(@PathVariable String code,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest req) {
        return service.startTask(code, body, CurrentUsers.get(req));
    }

    @PostMapping("/tasks/{code}/complete")
    public Map<String, Object> complete(@PathVariable String code,
                                        @RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest req) {
        return service.completeTask(code, body, CurrentUsers.get(req));
    }

    @PostMapping("/tasks/{code}/cancel")
    public Map<String, Object> cancel(@PathVariable String code,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest req) {
        return service.cancelTask(code, body, CurrentUsers.get(req));
    }

    // ---------------- 污染轮次 / 流程卡 / 复核 / 回执 ----------------

    @PostMapping("/rounds/{code}/exit")
    public Map<String, Object> exit(@PathVariable String code,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest req) {
        return service.exitRound(code, body, CurrentUsers.get(req));
    }

    @PostMapping("/rounds/{code}/step")
    public Map<String, Object> step(@PathVariable String code,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest req) {
        return service.recordStep(code, body, CurrentUsers.get(req));
    }

    @PostMapping("/rounds/{code}/complete-card")
    public Map<String, Object> completeCard(@PathVariable String code,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest req) {
        return service.completeCard(code, body, CurrentUsers.get(req));
    }

    @PostMapping("/rounds/{code}/review")
    public Map<String, Object> review(@PathVariable String code,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest req) {
        return service.reviewRound(code, body, CurrentUsers.get(req));
    }

    @PostMapping("/rounds/{code}/exit-receipt")
    public Map<String, Object> exitReceipt(@PathVariable String code,
                                           @RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest req) {
        return service.receiveExitReceipt(code, body, CurrentUsers.get(req));
    }

    // ---------------- 防护用品库存 ----------------

    @PostMapping("/ppe/stock/{sku}/restock")
    public Map<String, Object> restock(@PathVariable String sku,
                                        @RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest req) {
        return service.restock(sku, body, CurrentUsers.get(req));
    }

    @PostMapping("/ppe/stock/{sku}/adjust")
    public Map<String, Object> adjust(@PathVariable String sku,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest req) {
        return service.adjust(sku, body, CurrentUsers.get(req));
    }
}
