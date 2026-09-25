package com.storagehub.service;

import com.storagehub.domain.model.LoginHistory;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.LoginHistoryRepository;
import com.storagehub.domain.repo.UserRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoginHistoryService {

    private final LoginHistoryRepository repository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User user, String email, boolean success, String ip, String userAgent, String failureReason) {
        LoginHistory history = new LoginHistory();
        UUID userId = user == null ? null : user.getId();
        history.setUser(userId == null ? null : userRepository.getReferenceById(userId));
        history.setEmailAttempted(email);
        history.setSuccess(success);
        history.setIpAddress(ip);
        history.setUserAgent(userAgent);
        history.setFailureReason(failureReason);
        history.setOccurredAt(Instant.now());
        repository.save(history);
    }
}
