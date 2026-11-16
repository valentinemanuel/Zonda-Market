package com.zondamarket.shared.status;

import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final BuildProperties build;

    public StatusController(BuildProperties build) {
        this.build = build;
    }

    @GetMapping("/api/v1/status")
    public ResponseEntity<StatusResponse> status() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new StatusResponse("UP", build.getVersion()));
    }
}
