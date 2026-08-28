package com.example.nav.module.bookmark.controller;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.dto.VisibilityDTO;
import com.example.nav.common.result.Result;
import com.example.nav.module.bookmark.dto.BookmarkBatchMoveDTO;
import com.example.nav.module.bookmark.dto.BookmarkCreateDTO;
import com.example.nav.module.bookmark.dto.BookmarkUpdateDTO;
import com.example.nav.module.bookmark.service.BookmarkService;
import com.example.nav.module.bookmark.vo.BookmarkVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/admin/bookmarks")
@SecurityRequirement(name = "bearerAuth")
public class BookmarkController {

    private final BookmarkService bookmarkService;

    public BookmarkController(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @GetMapping
    @Operation(summary = "书签列表，可按 categoryId 筛选")
    public Result<List<BookmarkVO>> list(@RequestParam(required = false) Long categoryId) {
        return Result.success(bookmarkService.list(categoryId));
    }

    @PostMapping
    @Operation(summary = "新增书签")
    public Result<BookmarkVO> create(@Valid @RequestBody BookmarkCreateDTO createDTO) {
        return Result.success(bookmarkService.create(createDTO));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新书签")
    public Result<BookmarkVO> update(@PathVariable Long id, @Valid @RequestBody BookmarkUpdateDTO updateDTO) {
        return Result.success(bookmarkService.update(id, updateDTO));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除书签")
    public Result<Void> delete(@PathVariable Long id) {
        bookmarkService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/visible")
    @Operation(summary = "设置书签显隐")
    public Result<BookmarkVO> visible(@PathVariable Long id, @Valid @RequestBody VisibilityDTO visibilityDTO) {
        return Result.success(bookmarkService.setVisible(id, visibilityDTO.visible()));
    }

    @PutMapping("/batch-move")
    @Operation(summary = "批量移动书签到目标分类，并按请求顺序追加到分类末尾")
    public Result<List<BookmarkVO>> batchMove(@Valid @RequestBody BookmarkBatchMoveDTO moveDTO) {
        return Result.success(bookmarkService.batchMove(moveDTO));
    }

    @PutMapping("/sort")
    @Operation(summary = "批量调整书签排序")
    public Result<List<BookmarkVO>> sort(
            @NotEmpty(message = "排序列表不能为空")
            @Size(max = 1000, message = "排序列表不能超过 1000 项")
            @RequestBody List<@Valid SortItemDTO> items
    ) {
        return Result.success(bookmarkService.sort(items));
    }
}
