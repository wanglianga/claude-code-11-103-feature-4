package com.medwaste.track;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 公开查询：无需登录，任何人可查保管方与未结差异。 */
@RestController
@RequestMapping("/api/track")
public class TrackController {
    private final TrackService track;

    public TrackController(TrackService track) {
        this.track = track;
    }

    @GetMapping("/open-discrepancies")
    public List<Map<String, Object>> openDiscrepancies() {
        return track.publicOpenDiscrepancies();
    }

    @GetMapping("/{code}")
    public Map<String, Object> track(@PathVariable String code) {
        return track.track(code);
    }
}
