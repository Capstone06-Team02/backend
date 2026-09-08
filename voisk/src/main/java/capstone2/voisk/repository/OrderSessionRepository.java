package capstone2.voisk.repository;

import capstone2.voisk.entity.OrderSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderSessionRepository extends JpaRepository<OrderSession, Long> {

    void deleteByUpdatedAtBefore(LocalDateTime threshold);

    @Query("""
            SELECT COALESCE(s.clientSessionId, s.id) AS sessionId,
                   m.name AS menuName
            FROM OrderSession s
            JOIN s.orderMenus om
            JOIN om.menu m
            WHERE s.cartId = :cartId
            ORDER BY s.createdAt ASC, om.id ASC
            """)
    List<CartMenuItemRow> findCartMenuItemsByCartId(@Param("cartId") String cartId);

    @Query("""
            SELECT s
            FROM OrderSession s
            WHERE s.cartId = :cartId
              AND (s.clientSessionId = :sessionId OR s.id = :sessionId)
            """)
    Optional<OrderSession> findCartSession(
            @Param("cartId") String cartId,
            @Param("sessionId") String sessionId
    );

    @Query("""
            SELECT s.id AS orderSessionId,
                   COALESCE(s.clientSessionId, s.id) AS sessionId,
                   s.cartId AS cartId,
                   st.id AS storeId,
                   st.name AS storeName,
                   s.createdAt AS orderedAt,
                   om.id AS orderMenuId,
                   m.menuId AS menuId,
                   m.name AS menuName,
                   om.quantity AS quantity,
                   m.price AS menuPrice,
                   om.priceWithOption AS unitPrice,
                   moi.id AS optionItemId,
                   ogt.name AS optionGroupName,
                   oit.name AS optionName,
                   moi.extraPrice AS optionExtraPrice,
                   omo.quantity AS optionQuantity
            FROM OrderSession s
            JOIN s.store st
            JOIN s.orderMenus om
            JOIN om.menu m
            LEFT JOIN om.orderMenuOptions omo
            LEFT JOIN omo.menuOptionItem moi
            LEFT JOIN moi.menuOptionGroup mog
            LEFT JOIN mog.optionGroupTemplate ogt
            LEFT JOIN moi.optionItemTemplate oit
            WHERE s.cartId = :cartId
            ORDER BY s.createdAt ASC, om.id ASC, omo.id ASC
            """)
    List<OwnerOrderRow> findOwnerOrderRowsByCartId(@Param("cartId") String cartId);

    @Query("""
            SELECT s.id AS orderSessionId,
                   COALESCE(s.clientSessionId, s.id) AS sessionId,
                   s.cartId AS cartId,
                   st.id AS storeId,
                   st.name AS storeName,
                   s.createdAt AS orderedAt,
                   om.id AS orderMenuId,
                   m.menuId AS menuId,
                   m.name AS menuName,
                   om.quantity AS quantity,
                   m.price AS menuPrice,
                   om.priceWithOption AS unitPrice,
                   moi.id AS optionItemId,
                   ogt.name AS optionGroupName,
                   oit.name AS optionName,
                   moi.extraPrice AS optionExtraPrice,
                   omo.quantity AS optionQuantity
            FROM OrderSession s, Cart c
            JOIN s.store st
            JOIN s.orderMenus om
            JOIN om.menu m
            LEFT JOIN om.orderMenuOptions omo
            LEFT JOIN omo.menuOptionItem moi
            LEFT JOIN moi.menuOptionGroup mog
            LEFT JOIN mog.optionGroupTemplate ogt
            LEFT JOIN moi.optionItemTemplate oit
            WHERE c.id = s.cartId
              AND c.confirmed = true
              AND st.id = :storeId
            ORDER BY s.createdAt DESC, s.cartId ASC, om.id ASC, omo.id ASC
            """)
    List<OwnerOrderRow> findOwnerOrderRowsByStoreId(@Param("storeId") Long storeId);

    interface CartMenuItemRow {
        String getSessionId();

        String getMenuName();
    }

    interface OwnerOrderRow {
        String getOrderSessionId();

        String getSessionId();

        String getCartId();

        Long getStoreId();

        String getStoreName();

        LocalDateTime getOrderedAt();

        Long getOrderMenuId();

        Long getMenuId();

        String getMenuName();

        Integer getQuantity();

        Integer getMenuPrice();

        Integer getUnitPrice();

        Long getOptionItemId();

        String getOptionGroupName();

        String getOptionName();

        Integer getOptionExtraPrice();

        Integer getOptionQuantity();
    }
}
