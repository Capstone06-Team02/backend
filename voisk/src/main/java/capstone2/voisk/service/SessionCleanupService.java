package capstone2.voisk.service;

import capstone2.voisk.repository.OrderSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionCleanupService {

    private static final int SESSION_TTL_MINUTES = 30;

    private final OrderSessionRepository sessionRepository;

    @Scheduled(fixedDelay = 60 * 60 * 1000) // 1시간마다 실행
    @Transactional
    public void deleteExpiredSessions() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(SESSION_TTL_MINUTES);
        sessionRepository.deleteByUpdatedAtBefore(threshold);
        log.info("[SessionCleanup] {}분 이상 비활성 세션 삭제 완료", SESSION_TTL_MINUTES);
    }
}
