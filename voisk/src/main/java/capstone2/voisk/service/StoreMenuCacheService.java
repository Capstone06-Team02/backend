package capstone2.voisk.service;

import capstone2.voisk.dto.MenuCacheResponse;
import capstone2.voisk.entity.Category;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.entity.OptionGroup;
import capstone2.voisk.entity.OptionItem;
import capstone2.voisk.entity.Store;
import capstone2.voisk.repository.MenuRepository;
import capstone2.voisk.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class StoreMenuCacheService {

    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;
    private final Map<Long, MenuCacheResponse> cache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public MenuCacheResponse cacheMenus(Long restaurantId) {
        if (restaurantId == null) {
            throw new IllegalArgumentException("restaurantId is required.");
        }

        Store store = storeRepository.findById(restaurantId)
                .orElseThrow(() -> new IllegalArgumentException("Store not found. id=" + restaurantId));

        List<MenuCacheResponse.MenuInfo> menus = menuRepository.findByStoreIdOrderByIdAsc(restaurantId)
                .stream()
                .map(this::toMenuInfo)
                .toList();

        MenuCacheResponse response = new MenuCacheResponse(
                store.getId(),
                store.getName(),
                Instant.now(),
                menus.size(),
                menus
        );
        cache.put(restaurantId, response);
        return response;
    }

    public Optional<MenuCacheResponse> getCachedMenus(Long restaurantId) {
        return Optional.ofNullable(cache.get(restaurantId));
    }

    private MenuCacheResponse.MenuInfo toMenuInfo(Menu menu) {
        return new MenuCacheResponse.MenuInfo(
                menu.getId(),
                menu.getName(),
                menu.getPrice(),
                menu.getDescription(),
                menu.getIsAvailable(),
                toCategoryInfo(menu.getCategory()),
                emptyIfNull(menu.getOptionGroups()).stream()
                        .map(this::toOptionGroupInfo)
                        .toList()
        );
    }

    private MenuCacheResponse.CategoryInfo toCategoryInfo(Category category) {
        if (category == null) {
            return null;
        }
        return new MenuCacheResponse.CategoryInfo(
                category.getId(),
                category.getName(),
                category.getDepth()
        );
    }

    private MenuCacheResponse.OptionGroupInfo toOptionGroupInfo(OptionGroup optionGroup) {
        OptionItem parentOptionItem = optionGroup.getParentOptionItem();
        return new MenuCacheResponse.OptionGroupInfo(
                optionGroup.getId(),
                parentOptionItem == null ? null : parentOptionItem.getId(),
                optionGroup.getName(),
                optionGroup.getIsRequired(),
                optionGroup.getMinSelect(),
                optionGroup.getMaxSelect(),
                optionGroup.getIsAvailable(),
                emptyIfNull(optionGroup.getOptionItems()).stream()
                        .map(this::toOptionItemInfo)
                        .toList()
        );
    }

    private MenuCacheResponse.OptionItemInfo toOptionItemInfo(OptionItem optionItem) {
        return new MenuCacheResponse.OptionItemInfo(
                optionItem.getId(),
                optionItem.getName(),
                optionItem.getExtraPrice(),
                optionItem.getIsAvailable(),
                optionItem.getDefaultQuantity(),
                optionItem.getMaxQuantity(),
                optionItem.getIsDefault()
        );
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
