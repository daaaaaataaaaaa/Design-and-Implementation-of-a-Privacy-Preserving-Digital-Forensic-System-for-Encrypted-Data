package com.bdic.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ml-control")
public class MlServiceController {

    private final MlServiceManager mlServiceManager;
    private final SearchableEncryptionFacade facade;

    public MlServiceController(MlServiceManager mlServiceManager, SearchableEncryptionFacade facade) {
        this.mlServiceManager = mlServiceManager;
        this.facade = facade;
    }

    @GetMapping("/status")
    MlServiceStatusResponse status() {
        return mlServiceManager.status();
    }

    @PostMapping("/start")
    MlServiceStatusResponse start(@RequestHeader(value = "Authorization", required = false) String authorization) {
        facade.requireSession(authorization);
        return mlServiceManager.start();
    }
}
