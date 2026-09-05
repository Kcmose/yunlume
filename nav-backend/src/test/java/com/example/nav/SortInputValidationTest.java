package com.example.nav;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.customlink.mapper.CustomLinkMapper;
import com.example.nav.module.customlink.service.impl.CustomLinkServiceImpl;
import com.example.nav.module.publicdata.PublicDataCacheInvalidator;
import com.example.nav.module.search.mapper.SearchEngineMapper;
import com.example.nav.module.search.service.impl.SearchEngineServiceImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SortInputValidationTest {
    @ParameterizedTest
    @MethodSource("invalidInputs")
    void invalidItemsAreRejectedBeforeLockingOrWriting(List<SortItemDTO> items) {
        var searches = mock(SearchEngineMapper.class);
        var links = mock(CustomLinkMapper.class);
        var invalidator = mock(PublicDataCacheInvalidator.class);
        var searchService = new SearchEngineServiceImpl(searches, invalidator);
        var linkService = new CustomLinkServiceImpl(links, invalidator);

        assertEquals(400, assertThrows(BusinessException.class, () -> searchService.sort(items)).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> linkService.sort(items)).getStatus().value());
        verifyNoInteractions(searches, links, invalidator);
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(List.of()),
                Arguments.of(Collections.singletonList(null)),
                Arguments.of(List.of(new SortItemDTO(null, 0))),
                Arguments.of(List.of(new SortItemDTO(0L, 0))),
                Arguments.of(List.of(new SortItemDTO(1L, null))),
                Arguments.of(List.of(new SortItemDTO(1L, -1))),
                Arguments.of(java.util.Arrays.asList(new SortItemDTO(1L, 100), null)),
                Arguments.of(List.of(new SortItemDTO(1L, 0), new SortItemDTO(1L, 100))));
    }
}
