package com.evops.papercomp.controller;

import com.evops.common.ApiResponse;
import com.evops.papercomp.dto.PickAlgorithmRequest;
import com.evops.papercomp.dto.PickAlgorithmVersionRequest;
import com.evops.papercomp.entity.PickAlgorithm;
import com.evops.papercomp.entity.PickAlgorithmVersion;
import com.evops.papercomp.service.PickAlgorithmService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 抽选算法模块（模块 + 版本 + 状态）。路径在 /api/** 下，自动走 HTTP Basic。
 */
@RestController
@RequestMapping("/api/pick-algorithms")
public class PickAlgorithmController {

    private final PickAlgorithmService pickAlgorithmService;

    public PickAlgorithmController(PickAlgorithmService pickAlgorithmService) {
        this.pickAlgorithmService = pickAlgorithmService;
    }

    @PostMapping
    public ApiResponse<PickAlgorithm> create(@Valid @RequestBody PickAlgorithmRequest request) {
        return ApiResponse.ok(pickAlgorithmService.createAlgorithm(request));
    }

    @GetMapping
    public ApiResponse<List<PickAlgorithm>> list(@RequestParam(required = false) String projectCode) {
        return ApiResponse.ok(pickAlgorithmService.listAlgorithms(projectCode));
    }

    @GetMapping("/{algorithmId}")
    public ApiResponse<PickAlgorithm> get(@PathVariable Long algorithmId) {
        return ApiResponse.ok(pickAlgorithmService.getAlgorithm(algorithmId));
    }

    @PostMapping("/{algorithmId}/versions")
    public ApiResponse<PickAlgorithmVersion> createVersion(
            @PathVariable Long algorithmId,
            @Valid @RequestBody PickAlgorithmVersionRequest request) {
        return ApiResponse.ok(pickAlgorithmService.createDraftVersion(algorithmId, request));
    }

    @GetMapping("/{algorithmId}/versions")
    public ApiResponse<List<PickAlgorithmVersion>> listVersions(@PathVariable Long algorithmId) {
        return ApiResponse.ok(pickAlgorithmService.listVersions(algorithmId));
    }

    @GetMapping("/versions/{versionId}")
    public ApiResponse<PickAlgorithmVersion> getVersion(@PathVariable Long versionId) {
        return ApiResponse.ok(pickAlgorithmService.getVersion(versionId));
    }

    /** 发布：DRAFT → ACTIVE，旧生效版自动置 DISABLED。 */
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<PickAlgorithmVersion> publish(@PathVariable Long versionId) {
        return ApiResponse.ok(pickAlgorithmService.publishVersion(versionId));
    }

    /** 人工停用生效版。 */
    @PostMapping("/versions/{versionId}/disable")
    public ApiResponse<PickAlgorithmVersion> disable(@PathVariable Long versionId) {
        return ApiResponse.ok(pickAlgorithmService.disableVersion(versionId));
    }
}
