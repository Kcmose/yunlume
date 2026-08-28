package com.example.nav.module.search.controller;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.dto.VisibilityDTO;
import com.example.nav.common.result.Result;
import com.example.nav.module.search.dto.SearchEngineDTO;
import com.example.nav.module.search.service.SearchEngineService;
import com.example.nav.module.search.vo.SearchEngineVO;
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
@RequestMapping("/api/admin/search-engines")
@SecurityRequirement(name = "bearerAuth")
public class SearchEngineController {

    private final SearchEngineService searchEngineService;

    public SearchEngineController(SearchEngineService searchEngineService) {
        this.searchEngineService = searchEngineService;
    }

    @GetMapping
    @Operation(summary = "搜索引擎列表")
    public Result<List<SearchEngineVO>> list() {
        return Result.success(searchEngineService.listAll());
    }

    @PostMapping
    @Operation(summary = "新增搜索引擎")
    public Result<SearchEngineVO> create(@Valid @RequestBody SearchEngineDTO dto) {
        return Result.success(searchEngineService.create(dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新搜索引擎")
    public Result<SearchEngineVO> update(@PathVariable Long id, @Valid @RequestBody SearchEngineDTO dto) {
        return Result.success(searchEngineService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除搜索引擎")
    public Result<Void> delete(@PathVariable Long id) {
        searchEngineService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/default")
    @Operation(summary = "设置默认搜索引擎")
    public Result<SearchEngineVO> setDefault(@PathVariable Long id) {
        return Result.success(searchEngineService.setDefault(id));
    }

    @PutMapping("/{id}/visible")
    @Operation(summary = "设置搜索引擎启用状态")
    public Result<SearchEngineVO> setVisible(
            @PathVariable Long id,
            @Valid @RequestBody VisibilityDTO visibilityDTO
    ) {
        return Result.success(searchEngineService.setVisible(id, visibilityDTO.visible()));
    }

    @PutMapping("/sort")
    @Operation(summary = "批量调整搜索引擎排序")
    public Result<List<SearchEngineVO>> sort(
            @NotEmpty(message = "排序列表不能为空") @RequestBody List<@Valid SortItemDTO> items
    ) {
        return Result.success(searchEngineService.sort(items));
    }
}
