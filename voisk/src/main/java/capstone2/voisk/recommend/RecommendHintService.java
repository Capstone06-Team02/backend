package capstone2.voisk.recommend;

import capstone2.voisk.entity.RecommendHint;
import capstone2.voisk.entity.RecommendHintMenu;
import capstone2.voisk.repository.RecommendHintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RecommendHintService {

    private final RecommendHintRepository recommendHintRepository;

    @Transactional(readOnly = true)
    public RecommendHintListResponse getHints(Long storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required.");
        }
        List<RecommendHintListResponse.HintInfo> hints = recommendHintRepository
                .findByStoreIdWithMenus(storeId).stream()
                .map(hint -> new RecommendHintListResponse.HintInfo(hint.getId(), hint.getLabel()))
                .toList();
        return new RecommendHintListResponse(hints);
    }

    @Transactional(readOnly = true)
    public RecommendHintResponse recommendByHint(Long hintId) {
        if (hintId == null) {
            throw new IllegalArgumentException("hintId is required.");
        }
        RecommendHint hint = recommendHintRepository.findByIdWithMenus(hintId)
                .orElseThrow(() -> new IllegalArgumentException("Recommend hint not found. hintId=" + hintId));

        List<RecommendHintResponse.MenuItem> menus = hint.getMenus().stream()
                .sorted((a, b) -> Integer.compare(a.getRank(), b.getRank()))
                .map(hm -> new RecommendHintResponse.MenuItem(
                        hm.getMenu().getMenuId(),
                        hm.getMenu().getName(),
                        hm.getMenu().getPrice()
                ))
                .toList();

        return new RecommendHintResponse(menus, buildTtsText(hint.getLabel(), menus));
    }

    private String buildTtsText(String hintLabel, List<RecommendHintResponse.MenuItem> menus) {
        if (menus.isEmpty()) {
            return "죄송합니다, 조건에 맞는 메뉴를 찾지 못했어요.";
        }
        String names = menus.stream()
                .map(RecommendHintResponse.MenuItem::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return "\"" + hintLabel + "\" 추천 메뉴로는 " + names + "가 있어요.";
    }
}
