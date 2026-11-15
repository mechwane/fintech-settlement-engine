package com.fintech.settlement_service.controller;

import com.fintech.settlement_service.model.DeadLetterEvent;
import com.fintech.settlement_service.repository.DeadLetterEventRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class DeadLetterAdminController {

    private final DeadLetterEventRepository dltRepository;

    public DeadLetterAdminController(DeadLetterEventRepository dltRepository) {
        this.dltRepository = dltRepository;
    }

    @GetMapping("/dlt")
    public List<DeadLetterEvent> getQuarantinedEvents() {
        return dltRepository.findAllByOrderByFailedAtDesc();
    }
}