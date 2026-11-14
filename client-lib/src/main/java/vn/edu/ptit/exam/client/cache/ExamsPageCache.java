package vn.edu.ptit.exam.client.cache;

import vn.edu.ptit.exam.common.dto.ExamListResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 30-second in-memory page cache for LIST_EXAMS results according to Section 4 / Luồng 2.
 * Automatically invalidated upon SUBMIT or manual refresh.
 */
public class ExamsPageCache {

    private static final long CACHE_TTL_MS = 30_000L; // 30 seconds

    private static class CacheEntry {
        final ExamListResponse data;
        final long createdAt;

        CacheEntry(ExamListResponse data, long createdAt) {
            this.data = data;
            this.createdAt = createdAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private String buildKey(String keyword, String status, int page, int pageSize) {
        return (keyword == null ? "" : keyword.trim().toLowerCase()) + "|" +
                (status == null ? "" : status.trim().toUpperCase()) + "|" +
                page + "|" + pageSize;
    }

    public ExamListResponse get(String keyword, String status, int page, int pageSize) {
        String key = buildKey(keyword, status, page, pageSize);
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            if (!entry.isExpired()) {
                return entry.data;
            } else {
                cache.remove(key);
            }
        }
        return null;
    }

    public void put(String keyword, String status, int page, int pageSize, ExamListResponse response) {
        if (response != null) {
            String key = buildKey(keyword, status, page, pageSize);
            cache.put(key, new CacheEntry(response, System.currentTimeMillis()));
        }
    }

    public void invalidateAll() {
        cache.clear();
    }
}
