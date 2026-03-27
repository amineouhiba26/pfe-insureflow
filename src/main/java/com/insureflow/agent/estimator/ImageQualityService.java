package com.insureflow.agent.estimator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Evaluates the quality of submitted damage photos.
 *
 * Returns a score from 0.0 to 1.0 used in the confidence formula:
 *   composite = (LLM confidence × 0.4) + (business rules × 0.4) + (image quality × 0.2)
 *
 * Quality criteria:
 * - No photos submitted    → 0.0
 * - Photos unreachable     → 0.2 (URL exists but can't connect)
 * - Few photos (1)         → 0.6
 * - Good number (2-3)      → 0.8
 * - Many photos (4+)       → 1.0
 *
 * In Sprint 7 this can be enhanced with actual image analysis
 * (resolution check, blur detection via vision model).
 * For now it uses URL reachability and photo count as a proxy.
 */
@Service
public class ImageQualityService {

    private static final Logger log = LoggerFactory.getLogger(ImageQualityService.class);

    public double evaluate(List<String> photoUrls) {
        if (photoUrls == null || photoUrls.isEmpty()) {
            log.debug("[IMAGE_QUALITY] No photos submitted → score 0.0");
            return 0.0;
        }

        int total       = photoUrls.size();
        int reachable   = countReachable(photoUrls);
        double coverage = total >= 4 ? 1.0 : total >= 2 ? 0.8 : 0.6;
        double access   = total > 0 ? (double) reachable / total : 0.0;

        // Weight: 60% photo count coverage + 40% reachability
        double score = (coverage * 0.6) + (access * 0.4);

        log.debug("[IMAGE_QUALITY] total={} reachable={} score={}",
                total, reachable, String.format("%.2f", score));

        return Math.min(1.0, score);
    }

    private int countReachable(List<String> urls) {
        int count = 0;
        for (String url : urls) {
            if (isReachable(url)) count++;
        }
        return count;
    }

    private boolean isReachable(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            log.debug("[IMAGE_QUALITY] URL not reachable: {}", urlStr);
            return false;
        }
    }
}