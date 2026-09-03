package capstone2.voisk.recommend;

import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** LLM 추천 ID를 원래 후보와 최신 DB 상태에 대조해 최종 메뉴를 결정한다. */
@Service
@RequiredArgsConstructor
public class RecommendationValidationService {

    private static final int MAX_LLM_CANDIDATES = 10;
    private static final int MAX_RESULTS = 3;

    private final MenuRepository menuRepository;

    @Transactional(readOnly = true)
    public List<Menu> validate(
            Long storeId,
            List<Long> originalCandidateIds,
            RecommendationConstraints constraints,
            List<Long> rankedMenuIds
    ) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required.");
        }
        if (constraints == null) {
            throw new IllegalArgumentException("recommendation constraints are required.");
        }
        Set<Long> originalCandidates = new LinkedHashSet<>(safeList(originalCandidateIds));

        List<Long> idsToValidate = safeList(rankedMenuIds).stream()
                .filter(id -> id != null && originalCandidates.contains(id))
                .distinct()
                .limit(MAX_LLM_CANDIDATES)
                .toList();
        if (idsToValidate.isEmpty()) {
            return List.of();
        }

        List<Menu> validMenus = menuRepository.findValidRecommendationMenus(
                idsToValidate,
                storeId,
                constraints.minPrice(),
                constraints.minPriceInclusive(),
                constraints.maxPrice(),
                constraints.maxPriceInclusive()
        );
        Map<Long, Menu> validById = validMenus.stream()
                .collect(Collectors.toMap(Menu::getMenuId, Function.identity(), (first, ignored) -> first));

        return idsToValidate.stream()
                .map(validById::get)
                .filter(java.util.Objects::nonNull)
                .limit(MAX_RESULTS)
                .toList();
    }

    private List<Long> safeList(List<Long> values) {
        return values == null ? List.of() : values;
    }
}
