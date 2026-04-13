package com.insureflow.agent.estimator;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ImageQualityService {

    public double evaluate(List<String> photoUrls) {
        if (photoUrls == null || photoUrls.isEmpty()) return 0.0;
        int count = photoUrls.size();
        if (count >= 4) return 1.0;
        if (count >= 2) return 0.8;
        return 0.6;
    }
}