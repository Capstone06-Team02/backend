-- store4 (N=100) — 펀넬 확장성 측정용. 앵커 10 + 필러 90(store3의 40 복사 + 신규 50).
-- 멱등성: 이미 있으면 건너뛰도록 store4 존재 시 중단 권장(아래는 1회 실행 가정).

INSERT INTO store (store_id, name) VALUES (4, '카페 보이스크 100');

INSERT INTO category (name, store_id) VALUES
  ('음료', 4), ('커피', 4), ('논커피', 4), ('디저트', 4);

SET @c_coffee    = (SELECT category_id FROM category WHERE store_id=4 AND name='커피');
SET @c_noncoffee = (SELECT category_id FROM category WHERE store_id=4 AND name='논커피');
SET @c_dessert   = (SELECT category_id FROM category WHERE store_id=4 AND name='디저트');

-- store3의 50개(앵커10+필러40)를 카테고리명 기준으로 매핑해 복사
INSERT INTO menu (store_id, category_id, name, description, price, is_available)
SELECT 4, c4.category_id, m.name, m.description, m.price, m.is_available
FROM menu m
JOIN category c3 ON m.category_id = c3.category_id AND c3.store_id = 3
JOIN category c4 ON c4.store_id = 4 AND c4.name = c3.name
WHERE m.store_id = 3;

-- 신규 필러 50개 (커피15 / 논커피20 / 디저트15)
INSERT INTO menu (store_id, category_id, name, description, price, is_available) VALUES
(4,@c_coffee,'비엔나커피','진한 커피 위에 부드러운 크림을 올린 비엔나커피',5800,b'1'),
(4,@c_coffee,'아포가토','바닐라 아이스크림에 에스프레소를 부은 아포가토',6000,b'1'),
(4,@c_coffee,'롱블랙','에스프레소에 물을 더한 진한 롱블랙',4800,b'1'),
(4,@c_coffee,'마키아토','에스프레소에 우유 거품을 살짝 올린 마키아토',4800,b'1'),
(4,@c_coffee,'오트밀라떼','오트밀크로 만든 고소한 라떼',6000,b'1'),
(4,@c_coffee,'바닐라콜드브루','콜드브루에 바닐라 향을 더한 시원한 커피',5800,b'1'),
(4,@c_coffee,'흑당라떼','진한 흑당 시럽을 넣은 달콤한 라떼',6000,b'1'),
(4,@c_coffee,'민트모카','상쾌한 민트와 초콜릿이 어우러진 모카',6000,b'1'),
(4,@c_coffee,'카라멜콜드브루','카라멜을 더한 부드러운 콜드브루',5800,b'1'),
(4,@c_coffee,'피넛라떼','고소한 땅콩 풍미의 라떼',6000,b'1'),
(4,@c_coffee,'로즈마리라떼','은은한 허브향의 시그니처 라떼',6200,b'1'),
(4,@c_coffee,'더치커피','오랜 시간 우려낸 부드러운 더치커피',5500,b'1'),
(4,@c_coffee,'디카페인콜드브루','카페인을 줄인 부드러운 콜드브루',5500,b'1'),
(4,@c_coffee,'단호박라떼','달큰한 단호박이 들어간 라떼',6000,b'1'),
(4,@c_coffee,'코코넛라떼','달콤한 코코넛 풍미의 라떼',6000,b'1'),
(4,@c_noncoffee,'자두에이드','새콤달콤한 자두 에이드',5800,b'1'),
(4,@c_noncoffee,'패션후르츠에이드','향긋한 패션후르츠 에이드',5800,b'1'),
(4,@c_noncoffee,'라임모히또','상큼한 라임과 민트의 무알콜 모히또',5800,b'1'),
(4,@c_noncoffee,'생딸기라떼','생딸기를 갈아넣은 시원한 라떼',5800,b'1'),
(4,@c_noncoffee,'초코민트','시원한 민트와 초콜릿 음료',5800,b'1'),
(4,@c_noncoffee,'곡물라떼','구수한 곡물로 만든 라떼',5800,b'1'),
(4,@c_noncoffee,'쑥라떼','향긋한 쑥이 들어간 그린 라떼',6000,b'1'),
(4,@c_noncoffee,'홍차라떼','진한 홍차와 우유의 밀크티 라떼',5500,b'1'),
(4,@c_noncoffee,'얼그레이밀크티','베르가못 향의 얼그레이 밀크티',5500,b'1'),
(4,@c_noncoffee,'루이보스티','카페인 없는 루이보스 허브티',5000,b'1'),
(4,@c_noncoffee,'히비스커스티','새콤한 붉은빛 히비스커스티',5000,b'1'),
(4,@c_noncoffee,'오미자에이드','다섯 가지 맛의 오미자 에이드',5800,b'1'),
(4,@c_noncoffee,'수박주스','시원하게 갈아낸 수박주스',6000,b'1'),
(4,@c_noncoffee,'키위주스','상큼한 키위 생과일주스',6000,b'1'),
(4,@c_noncoffee,'바나나우유','부드럽고 달콤한 바나나우유',5000,b'1'),
(4,@c_noncoffee,'생딸기우유','생딸기가 씹히는 딸기우유',5000,b'1'),
(4,@c_noncoffee,'멜론소다','톡 쏘는 청량한 멜론소다',5800,b'1'),
(4,@c_noncoffee,'청귤에이드','제주 청귤로 만든 상큼한 에이드',5800,b'1'),
(4,@c_noncoffee,'코코넛스무디','부드러운 코코넛 스무디',6200,b'1'),
(4,@c_noncoffee,'망고요거트스무디','망고와 요거트의 새콤달콤 스무디',6200,b'1'),
(4,@c_dessert,'플레인베이글','쫄깃한 플레인 베이글',4000,b'1'),
(4,@c_dessert,'크루아상','결결이 바삭한 버터 크루아상',4500,b'1'),
(4,@c_dessert,'초코머핀','진한 초코칩이 박힌 머핀',4000,b'1'),
(4,@c_dessert,'블루베리머핀','블루베리가 가득한 머핀',4000,b'1'),
(4,@c_dessert,'레몬파운드','상큼한 레몬 파운드케이크',4500,b'1'),
(4,@c_dessert,'생크림케이크','부드러운 생크림 조각케이크',7000,b'1'),
(4,@c_dessert,'초코케이크','진한 초콜릿 케이크',7000,b'1'),
(4,@c_dessert,'고구마케이크','달콤한 고구마 무스케이크',6800,b'1'),
(4,@c_dessert,'바스크치즈케이크','겉은 진하고 속은 촉촉한 바스크 치즈케이크',7000,b'1'),
(4,@c_dessert,'버터와플','바삭하고 달콤한 버터 와플',6000,b'1'),
(4,@c_dessert,'글레이즈드도넛','폭신한 글레이즈드 도넛',3500,b'1'),
(4,@c_dessert,'컵케이크','부드러운 크림을 올린 컵케이크',5500,b'1'),
(4,@c_dessert,'커스터드푸딩','부드러운 커스터드 푸딩',4000,b'1'),
(4,@c_dessert,'젤라또','진한 우유의 수제 젤라또',4000,b'1'),
(4,@c_dessert,'수제쿠키세트','다양한 수제 쿠키 세트',4500,b'1');

-- 결과 확인
SELECT store_id, COUNT(*) AS menus FROM menu WHERE store_id=4 GROUP BY store_id;
