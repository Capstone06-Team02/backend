-- Voisk store_id=1 메뉴 카테고리 분리 및 설명 개정
-- 대상 DB: MySQL (voisk)
-- 실행 조건: 백엔드를 중지한 상태에서 실행
-- 특성:
--   1) 티/에이드 통합 카테고리를 티와 에이드로 분리
--   2) 메뉴 ID가 아니라 store_id + 메뉴명으로 30개 설명 갱신
--   3) 같은 스크립트를 다시 실행해도 카테고리를 중복 생성하지 않음
--   4) 설명 갱신 후 임베딩 재생성 필요

USE voisk;
SET NAMES utf8mb4;
SET @store_id := 1;
SET @VOISK_OLD_SQL_SAFE_UPDATES := @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

-- -----------------------------------------------------------------------------
-- 0. 실행 전 확인
-- -----------------------------------------------------------------------------
SELECT store_id, name
FROM store
WHERE store_id = @store_id;

SELECT c.category_id, c.name AS category_name, COUNT(m.menu_id) AS menu_count
FROM category c
LEFT JOIN menu m ON m.category_id = c.category_id
WHERE c.store_id = @store_id
GROUP BY c.category_id, c.name
ORDER BY c.category_id;

START TRANSACTION;

-- -----------------------------------------------------------------------------
-- 1. 티 카테고리 확보
-- 우선순위: 기존 '티' -> '티/에이드' -> '티·에이드'
-- 현재 스냅샷에서는 category_id=3인 '티/에이드'가 재사용된다.
-- -----------------------------------------------------------------------------
SET @tea_category_id := COALESCE(
    (
        SELECT MIN(category_id)
        FROM category
        WHERE store_id = @store_id
          AND parent_category_id IS NULL
          AND name = '티'
    ),
    (
        SELECT MIN(category_id)
        FROM category
        WHERE store_id = @store_id
          AND parent_category_id IS NULL
          AND name = '티/에이드'
    ),
    (
        SELECT MIN(category_id)
        FROM category
        WHERE store_id = @store_id
          AND parent_category_id IS NULL
          AND name = '티·에이드'
    )
);

UPDATE category
SET name = '티'
WHERE category_id = @tea_category_id
  AND store_id = @store_id;

INSERT INTO category (name, depth, store_id, parent_category_id)
SELECT '티', 1, @store_id, NULL
WHERE EXISTS (
    SELECT 1 FROM store WHERE store_id = @store_id
)
  AND NOT EXISTS (
      SELECT 1
      FROM category
      WHERE store_id = @store_id
        AND parent_category_id IS NULL
        AND name = '티'
  );

SET @tea_category_id := (
    SELECT MIN(category_id)
    FROM category
    WHERE store_id = @store_id
      AND parent_category_id IS NULL
      AND name = '티'
);

-- -----------------------------------------------------------------------------
-- 2. 에이드 카테고리 확보
-- 우선순위: 기존 '에이드' -> 티로 사용하지 않은 통합 카테고리 -> 새 카테고리
-- 현재 스냅샷에서는 category_id=8인 '티·에이드'가 재사용된다.
-- -----------------------------------------------------------------------------
SET @ade_category_id := COALESCE(
    (
        SELECT MIN(category_id)
        FROM category
        WHERE store_id = @store_id
          AND parent_category_id IS NULL
          AND name = '에이드'
    ),
    (
        SELECT MIN(category_id)
        FROM category
        WHERE store_id = @store_id
          AND parent_category_id IS NULL
          AND name IN ('티/에이드', '티·에이드')
          AND category_id <> @tea_category_id
    )
);

UPDATE category
SET name = '에이드'
WHERE category_id = @ade_category_id
  AND store_id = @store_id;

INSERT INTO category (name, depth, store_id, parent_category_id)
SELECT '에이드', 1, @store_id, NULL
WHERE EXISTS (
    SELECT 1 FROM store WHERE store_id = @store_id
)
  AND NOT EXISTS (
      SELECT 1
      FROM category
      WHERE store_id = @store_id
        AND parent_category_id IS NULL
        AND name = '에이드'
  );

SET @ade_category_id := (
    SELECT MIN(category_id)
    FROM category
    WHERE store_id = @store_id
      AND parent_category_id IS NULL
      AND name = '에이드'
);

-- -----------------------------------------------------------------------------
-- 3. 티/에이드 메뉴 재분류
-- 아샷추는 아이스티를 기반으로 하므로 티 카테고리로 분류한다.
-- -----------------------------------------------------------------------------
UPDATE menu
SET category_id = @tea_category_id
WHERE store_id = @store_id
  AND name IN (
      '자몽 허니 블랙 티',
      '아샷추',
      '캐모마일 티',
      '페퍼민트 티',
      '유자차'
  );

UPDATE menu
SET category_id = @ade_category_id
WHERE store_id = @store_id
  AND name IN (
      '레몬 에이드',
      '청포도 에이드'
  );

-- 다른 메뉴나 하위 카테고리가 남아 있지 않은 기존 통합 카테고리만 제거한다.
DELETE c
FROM category c
LEFT JOIN menu remaining_menu ON remaining_menu.category_id = c.category_id
LEFT JOIN category child ON child.parent_category_id = c.category_id
WHERE c.store_id = @store_id
  AND c.name IN ('티/에이드', '티·에이드')
  AND remaining_menu.menu_id IS NULL
  AND child.category_id IS NULL;

-- -----------------------------------------------------------------------------
-- 4. 메뉴 설명 개정안
-- 실제 레시피·당도·카페인 정보 확인 후 실행한다.
-- -----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_voisk_menu_description;
CREATE TEMPORARY TABLE tmp_voisk_menu_description (
    menu_name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    PRIMARY KEY (menu_name)
) ENGINE=Memory DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_voisk_menu_description (menu_name, description)
VALUES
    ('아메리카노',
     '에스프레소에 물을 더한 깔끔한 커피입니다. 단맛은 거의 없고 쓴맛은 보통이며 원두 향이 또렷합니다.'),
    ('카페 라떼',
     '에스프레소와 우유가 어우러진 부드러운 커피입니다. 단맛은 낮은 편이고 우유의 고소함이 느껴집니다.'),
    ('슈크림 라떼',
     '에스프레소와 우유에 슈크림 풍미를 더한 부드러운 라떼입니다. 단맛은 높은 편이며 고소한 맛이 느껴집니다.'),
    ('자몽 허니 블랙 티',
     '자몽의 상큼한 산미와 쌉싸름함에 꿀의 은은한 단맛이 더해진 블랙티입니다. 단맛은 보통이고 카페인이 있습니다.'),
    ('아샷추',
     '아이스티에 에스프레소 샷을 더한 차가운 음료입니다. 단맛은 보통이며 차의 산뜻함과 커피의 쓴맛이 함께 느껴집니다.'),
    ('딸기 크림 프라푸치노',
     '딸기와 크림을 얼음과 함께 갈아 만든 부드러운 음료입니다. 단맛은 높은 편이고 딸기의 가벼운 산미가 느껴집니다.'),
    ('햄 치즈 샌드위치',
     '햄과 치즈를 넣은 담백한 샌드위치입니다. 짠맛과 고소함은 보통이며 간단한 식사로 적합합니다.'),
    ('에그 마요 샌드위치',
     '에그 마요를 넣은 부드러운 샌드위치입니다. 단맛은 낮은 편이고 고소하며 가벼운 식사로 적합합니다.'),
    ('치킨 클럽 샌드위치',
     '치킨과 채소를 넣은 담백한 샌드위치입니다. 포만감이 높아 식사 대용으로 적합합니다.'),
    ('부드러운 생크림 카스텔라',
     '폭신한 카스텔라에 생크림을 더한 부드러운 케이크입니다. 단맛은 보통이며 가벼운 디저트로 적합합니다.'),
    ('초콜릿 가나슈 케이크',
     '진한 초콜릿 가나슈를 사용한 묵직한 케이크입니다. 단맛은 높은 편이고 초콜릿의 쌉싸름함이 느껴집니다.'),
    ('바스크 치즈 케이크',
     '진한 치즈 풍미와 꾸덕한 질감이 특징인 케이크입니다. 단맛은 보통이며 고소한 맛이 느껴집니다.'),
    ('에스프레소',
     '원두를 진하게 추출한 양이 적은 커피입니다. 단맛은 거의 없고 쓴맛과 원두 향이 강하게 느껴집니다.'),
    ('카푸치노',
     '에스프레소에 우유와 풍성한 거품을 더한 부드러운 커피입니다. 단맛은 낮은 편이고 고소한 맛이 느껴집니다.'),
    ('바닐라 라떼',
     '에스프레소와 우유에 바닐라 향을 더한 부드러운 라떼입니다. 단맛은 높은 편이고 고소한 맛이 느껴집니다.'),
    ('카페 모카',
     '에스프레소와 우유에 초콜릿을 더한 진한 커피입니다. 단맛은 높은 편이며 커피의 쓴맛과 초콜릿 풍미가 함께 느껴집니다.'),
    ('콜드브루',
     '찬물로 오래 추출해 쓴맛을 줄인 차가운 커피입니다. 단맛은 거의 없고 뒷맛이 깔끔합니다.'),
    ('디카페인 아메리카노',
     '카페인을 줄인 원두에 물을 더한 깔끔한 커피입니다. 단맛은 거의 없고 쓴맛은 보통입니다.'),
    ('말차 라떼',
     '진한 말차와 우유가 어우러진 부드러운 논커피 라떼입니다. 단맛은 낮은 편이고 쌉싸름하며 고소한 맛이 느껴집니다.'),
    ('초콜릿 라떼',
     '초콜릿과 우유로 만든 부드러운 무카페인 음료입니다. 단맛은 높은 편이고 진한 초콜릿 풍미가 느껴집니다.'),
    ('고구마 라떼',
     '군고구마와 우유로 만든 부드러운 무카페인 라떼입니다. 단맛은 보통이며 우유의 고소함이 느껴집니다.'),
    ('흑임자 라떼',
     '흑임자와 우유로 만든 진하고 부드러운 무카페인 라떼입니다. 단맛은 낮은 편이고 고소함이 강하게 느껴집니다.'),
    ('캐모마일 티',
     '캐모마일 향이 은은한 무카페인 허브티입니다. 단맛과 산미가 거의 없으며 질감과 향이 부드럽습니다.'),
    ('페퍼민트 티',
     '민트 향의 상쾌함이 특징인 무카페인 허브티입니다. 단맛과 산미가 거의 없고 뒷맛이 깔끔합니다.'),
    ('유자차',
     '유자의 상큼한 향과 산미에 부드러운 단맛이 더해진 무카페인 차입니다. 단맛은 보통입니다.'),
    ('레몬 에이드',
     '레몬의 선명한 상큼함과 높은 산미, 탄산의 청량감이 어우러진 차가운 음료입니다. 단맛은 낮은 편입니다.'),
    ('청포도 에이드',
     '청포도의 상큼한 산미와 은은한 단맛에 탄산의 청량감이 더해진 차가운 음료입니다. 단맛은 낮은 편입니다.'),
    ('망고 요거트 스무디',
     '망고의 진한 단맛과 요거트의 산미가 어우러진 부드럽고 차가운 음료입니다. 단맛은 높은 편입니다.'),
    ('플레인 베이글',
     '담백하고 쫄깃한 식감의 베이글입니다. 단맛은 낮은 편이고 포만감이 있어 간단한 식사 대용으로 적합합니다.'),
    ('닭가슴살 샐러드',
     '닭가슴살과 채소를 함께 담은 담백한 샐러드입니다. 단맛은 낮은 편이고 포만감이 있어 식사 대용으로 적합합니다.');

-- 30개 메뉴가 모두 존재하는지 확인한다.
SELECT
    COUNT(*) AS expected_menu_count,
    COUNT(DISTINCT m.name) AS matched_menu_count,
    GROUP_CONCAT(
        CASE WHEN m.menu_id IS NULL THEN t.menu_name END
        ORDER BY t.menu_name SEPARATOR ', '
    ) AS missing_menu_names
FROM tmp_voisk_menu_description t
LEFT JOIN menu m
  ON m.store_id = @store_id
 AND m.name = t.menu_name;

SET @matched_menu_count := (
    SELECT COUNT(DISTINCT m.name)
    FROM tmp_voisk_menu_description t
    JOIN menu m
      ON m.store_id = @store_id
     AND m.name = t.menu_name
);

UPDATE menu m
JOIN tmp_voisk_menu_description t ON t.menu_name = m.name
SET m.description = t.description
WHERE m.store_id = @store_id
  AND @matched_menu_count = 30;

-- -----------------------------------------------------------------------------
-- 5. 결과 검증
-- -----------------------------------------------------------------------------
SELECT @tea_category_id AS tea_category_id,
       @ade_category_id AS ade_category_id;

SELECT c.category_id,
       c.name AS category_name,
       COUNT(m.menu_id) AS menu_count,
       GROUP_CONCAT(m.name ORDER BY m.menu_id SEPARATOR ', ') AS menus
FROM category c
LEFT JOIN menu m ON m.category_id = c.category_id
WHERE c.store_id = @store_id
  AND c.name IN ('티', '에이드')
GROUP BY c.category_id, c.name
ORDER BY c.name;

SELECT
    COUNT(*) AS expected_menu_count,
    COUNT(DISTINCT CASE WHEN m.description = t.description THEN m.name END) AS updated_menu_count
FROM tmp_voisk_menu_description t
LEFT JOIN menu m
  ON m.store_id = @store_id
 AND m.name = t.menu_name;

SELECT m.menu_id,
       c.name AS category_name,
       m.name,
       m.description
FROM menu m
JOIN category c ON c.category_id = m.category_id
JOIN tmp_voisk_menu_description t ON t.menu_name = m.name
WHERE m.store_id = @store_id
ORDER BY c.name, m.menu_id;

COMMIT;

DROP TEMPORARY TABLE IF EXISTS tmp_voisk_menu_description;
SET SQL_SAFE_UPDATES = @VOISK_OLD_SQL_SAFE_UPDATES;

-- -----------------------------------------------------------------------------
-- 6. 실행 후 필수 작업
-- -----------------------------------------------------------------------------
-- 1) EMBED_MODEL=e5-base로 임베딩 서버를 실행한다.
-- 2) 백엔드를 재기동한다.
-- 3) embedding_source가 기존 e5-base에서 e5-base+text-v2로 달라지면서 30개 메뉴가 재임베딩되는지 로그를 확인한다.
--
-- 이미 e5-base+text-v2로 재임베딩한 뒤 이 SQL을 실행했다면 설명 변경이 자동 감지되지 않는다.
-- 그 경우 PostgreSQL의 해당 menu_embedding을 삭제한 뒤 백엔드를 재기동하거나,
-- MenuEmbeddingService의 PASSAGE_FORMAT_VERSION을 다시 올려 재임베딩한다.
