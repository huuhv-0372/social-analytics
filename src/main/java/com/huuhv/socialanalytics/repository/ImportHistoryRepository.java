package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.ImportHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportHistoryRepository extends JpaRepository<ImportHistory, Long> {
}
