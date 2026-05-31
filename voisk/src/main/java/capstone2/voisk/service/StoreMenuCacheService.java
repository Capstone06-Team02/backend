package capstone2.voisk.service;

import capstone2.voisk.converter.MenuCacheResponseConverter;
import capstone2.voisk.dto.MenuCacheResponse;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.entity.Store;
import capstone2.voisk.repository.MenuRepository;
import capstone2.voisk.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class StoreMenuCacheService {

    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;
    private final MenuCacheResponseConverter menuCacheResponseConverter;
    private final Map<Long, MenuCacheResponse> cache = new ConcurrentHashMap<>();
    private volatile Long latestRestaurantId;

    @Transactional(readOnly = true)
    public MenuCacheResponse cacheMenus(Long restaurantId) {
        if (restaurantId == null) {
            throw new IllegalArgumentException("restaurantId is required.");
        }

        Store store = storeRepository.findById(restaurantId)
                .orElseThrow(() -> new IllegalArgumentException("Store not found. id=" + restaurantId));

        List<Menu> menus = menuRepository.findByStoreIdOrderByMenuIdAsc(restaurantId);
        MenuCacheResponse response = menuCacheResponseConverter.toResponse(store, menus);
        cache.put(restaurantId, response);
        latestRestaurantId = restaurantId;
        return response;
    }

    public Optional<MenuCacheResponse> getCachedMenus(Long restaurantId) {
        return Optional.ofNullable(cache.get(restaurantId));
    }

    public Optional<MenuCacheResponse> getLatestCachedMenus() {
        return latestRestaurantId == null ? Optional.empty() : getCachedMenus(latestRestaurantId);
    }
}
