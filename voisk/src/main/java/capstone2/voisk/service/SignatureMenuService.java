package capstone2.voisk.service;

import capstone2.voisk.dto.SignatureMenuListResponse;
import capstone2.voisk.entity.Category;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.entity.Store;
import capstone2.voisk.repository.MenuRepository;
import capstone2.voisk.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SignatureMenuService {

    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;

    @Transactional(readOnly = true)
    public SignatureMenuListResponse getSignatureMenus(Long storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required.");
        }

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("Store not found. id=" + storeId));

        List<SignatureMenuListResponse.SignatureMenuInfo> menus = menuRepository
                .findSignatureMenusByStoreIdWithCategory(storeId)
                .stream()
                .map(this::toMenuInfo)
                .toList();

        return new SignatureMenuListResponse(
                store.getId(),
                store.getName(),
                menus.size(),
                menus
        );
    }

    private SignatureMenuListResponse.SignatureMenuInfo toMenuInfo(Menu menu) {
        return new SignatureMenuListResponse.SignatureMenuInfo(
                menu.getMenuId(),
                menu.getName(),
                menu.getPrice(),
                menu.getDescription(),
                menu.getIsAvailable(),
                menu.getIsSignature(),
                toCategoryInfo(menu.getCategory())
        );
    }

    private SignatureMenuListResponse.CategoryInfo toCategoryInfo(Category category) {
        if (category == null) {
            return null;
        }
        return new SignatureMenuListResponse.CategoryInfo(
                category.getCategoryId(),
                category.getName(),
                category.getDepth()
        );
    }
}
