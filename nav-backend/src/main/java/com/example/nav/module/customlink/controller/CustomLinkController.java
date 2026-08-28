package com.example.nav.module.customlink.controller;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.dto.VisibilityDTO;
import com.example.nav.common.result.Result;
import com.example.nav.module.customlink.dto.CustomLinkDTO;
import com.example.nav.module.customlink.service.CustomLinkService;
import com.example.nav.module.customlink.vo.CustomLinkVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/admin/custom-links")
@SecurityRequirement(name = "bearerAuth")
public class CustomLinkController {

    private final CustomLinkService customLinkService;

    public CustomLinkController(CustomLinkService customLinkService) {
        this.customLinkService = customLinkService;
    }

    @GetMapping
    @Operation(summary = "自定义链接列表")
    public Result<List<CustomLinkVO>> list() {
        return Result.success(customLinkService.listAll());
    }

    @PostMapping
    @Operation(summary = "新增自定义链接")
    public Result<CustomLinkVO> create(@Valid @RequestBody CustomLinkDTO dto) {
        return Result.success(customLinkService.create(dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新自定义链接")
    public Result<CustomLinkVO> update(@PathVariable Long id, @Valid @RequestBody CustomLinkDTO dto) {
        return Result.success(customLinkService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除自定义链接")
    public Result<Void> delete(@PathVariable Long id) {
        customLinkService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/visible")
    @Operation(summary = "设置自定义链接显示状态")
    public Result<CustomLinkVO> visible(
            @PathVariable Long id,
            @Valid @RequestBody VisibilityDTO visibilityDTO
    ) {
        return Result.success(customLinkService.setVisible(id, visibilityDTO.visible()));
    }

    @PutMapping("/sort")
    @Operation(summary = "批量调整自定义链接排序")
    public Result<List<CustomLinkVO>> sort(
            @NotEmpty(message = "排序列表不能为空") @RequestBody List<@Valid SortItemDTO> items
    ) {
        return Result.success(customLinkService.sort(items));
    }
}
